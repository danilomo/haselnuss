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
  function itself -- calling `:emit` is each emitter core's own job
  (`haselnuss.emit.html`/`haselnuss.emit.latex`'s own `render-directive`,
  TASK-19/21/89 -- corrected below, this namespace's own docstring
  overclaimed \"never called\" until TASK-89: an `:emit` value is called,
  routinely, by exactly one of those two functions, once a directive's own
  name resolves to it); calling `:lower` is TASK-17's `lower` pass -- it
  only stores and retrieves the data below, opaque past the shape
  `register`/`renderer-v2` document. `numbering-lexicon` is the one
  function that bridges to `haselnuss.resolver/number-document`'s own
  lexicon-merge convention (TASK-11 AC #4), and it does so by producing a
  plain map a caller passes in explicitly, rather than importing
  `haselnuss.resolver` to do it automatically -- keeping the dependency
  direction the caller's choice, not this namespace's.")

(defn register
  "Adds `extension` to `registry` (a plain map, `{}` for a fresh one),
  returning the updated registry. `extension` is `{:name :kind? :emit?
  :lower? :trusted?}` (AC #1):

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

    TASK-89 settled the renderer call signature TASK-19/21 had left
    open, and it is a per-value choice, not a per-registry one: a value
    here is either

    - a *v2* renderer, `(fn [node ctx] -> data)` -- `node` the Directive
      Block being rendered, `ctx` the calling emitter's own full
      rendering context (carrying, among other things, `:render-blocks`/
      `:render-inlines` callbacks the renderer can use to render its own
      nested content through the very emitter dispatching to it) -- built
      by wrapping an ordinary two-argument function in `renderer-v2`,
      which is what an emitter's `render-directive` recognizes via
      `v2?` to call it this way at all. Its return value is DATA the
      calling emitter serializes and escapes itself (hiccup-shaped data
      for `haselnuss.emit.html`, a small vocabulary of escaped text,
      `[:raw ...]` and pre-rendered fragments for `haselnuss.emit.latex`
      -- see each namespace's own docstring for the exact shape), which
      is what makes injection structurally impossible rather than merely
      filtered, and what applies the same escaping and (on the `:latex`
      target) the same unrepresentable-character reporting authored
      prose already gets to a renderer's own output, for free -- true
      without qualification for a plain string leaf, since that always
      goes through `escape-tex`/`escape-html` the same way authored text
      does. A `:raw` node -- the one way to emit genuinely verbatim,
      unescaped output, for content that has to be (a browser-only
      interactive widget is doc-5's own motivating case) -- is honored
      only when THIS registration's own `:trusted` is true; an untrusted
      `:raw` attempt is a loud `ex-info`, never a silent downgrade or a
      silent bypass, so a renderer cannot opt itself into trust by
      returning the right shape. `:raw` output ITSELF is where the
      unrepresentable-character/URL reporting parity is narrower, not
      absolute -- see `:trusted?` below.
    - a *legacy* renderer, the original `(fn [node target] -> string)` --
      `target` the plain target keyword (`:html`/`:latex`/...), no
      `ctx`, and its returned string is spliced into the output verbatim,
      exactly as it always has been. Kept working, deprecated, so an
      extension registered before TASK-89 is not broken by it: nothing
      about a plain function value's own shape can tell these two
      callers apart AFTER the fact (both are 2-argument functions, and a
      returned string cannot say whether it wants to be escaped text or
      verbatim markup), which is why the choice is an explicit opt-in a
      renderer's *author* makes at registration time (`renderer-v2`),
      not something either emitter infers.

    Calling a registered `:emit` value (either shape) is each emitter
    core's own job -- see the `:kind`/`:lower` bullets below for the
    identical division of labour those two already have, and this
    namespace's own docstring above for why this namespace itself never
    does it.
  - `:trusted?` -- optional, defaults to false. True only for a
    registration whose renderer output is allowed to include a
    verbatim, unescaped `:raw` node (see `:emit` above) on any target
    that honors one. Deliberately per-*registration* (one flag governing
    every target an extension renders for) rather than per-target or
    per-call: doc-6's own `--trusted-extensions` sketch is a decision
    about an extension *source* as a whole, made once by whoever loads
    it (`haselnuss.cli`, today; a future sandboxed loader, TASK-90), and
    never by the extension's own renderer code -- a v2 renderer has no
    way to read or set its own entry's `:trusted`, so an untrusted
    extension cannot elevate itself by constructing the right-looking
    data.

    Honest, documented residual limitation (TASK-89 review finding #2 --
    doc-6's own \"the honest limit... belongs in the documentation
    rather than papered over\" is the standard this is held to): a
    `:trusted` renderer's own `[:raw ...]` text is scanned for the one
    LaTeX path hazard this codebase already knows how to detect --
    `\\includegraphics{...}` carrying a raw backslash or an unbalanced
    brace (`haselnuss.emit.latex/unescapable-image-path-text-
    diagnostics`, TASK-78's own `image-path-hazards`, reused rather than
    duplicated) -- the same way `:raw` text is scanned for an
    unrepresentable character. It is NOT scanned for every OTHER
    path-bearing LaTeX construct a `:raw` node could in principle write
    (a raw `\\href`, an `\\input`, a hand-rolled macro that itself wraps
    `\\includegraphics` a layer down): those reach the emitted `.tex`
    with no warning at all if they carry the same class of hazard.
    Trusting a renderer's `:raw` output therefore means trusting it not
    to hand LaTeX a path it cannot read, for every shape this codebase
    does not already check -- exactly the kind of author-supplied-code
    trust doc-6's own threat model already accepts for `:trusted?` as a
    whole (\"prevent accident and casual mischief, not a determined
    attacker\"), named explicitly here rather than left to be
    discovered.

    One more limitation worth being precise about (TASK-89 review
    comment #11, sharpened by comments #16-18), because it is a property
    of the SCANNING FUNCTION itself, not of trust or of `:raw`
    specifically, and applies UNIVERSALLY, to a core-authored Image
    exactly as much as to a `:trusted` renderer's own `[:raw ...]`
    output: `unescapable-image-path-text-diagnostics` can misjudge a path
    whose own hazard is a stray CLOSING brace with nothing open before it
    (mirroring what pdflatex's own tokenizer actually does with the
    surrounding braces, rather than re-deriving `image-path-hazards`' own
    in-isolation verdict -- see that function's own docstring for the
    full reasoning), for ANY `\\includegraphics{...}` it is handed. A
    round of this task's own review (comment #11) once had
    `haselnuss.cli` also run the AST-based `unescapable-image-path-
    diagnostics` directly on every core Image, alongside the text-based
    scan, specifically to narrow this gap to `:trusted` `[:raw ...]`
    output only -- but reconciling two scans over genuinely overlapping
    content (every core Image reaches the text-based scan too, as its own
    emitted `\\includegraphics{...}`) needed a comparison key with no
    notion of WHICH occurrence a diagnostic was about, and repeated
    attempts to make that key correct kept closing one live-reproduced
    false positive or false negative and opening another (TASK-89 review
    comments #12-14, #16, #17). `haselnuss.cli` now calls only the
    text-based scan (comment #18's own call, made rather than patch the
    reconciliation a sixth time), so this limitation is no longer
    narrowed to `:trusted` output -- it is the same gap TASK-78 already
    accepts for any other path-bearing construct this codebase does not
    check at all, just for one specific hazard shape of the ONE
    construct it does. Severity stays bounded either way: a document
    with this exact shape still fails to compile under a real `pdflatex`,
    just without this earlier, more specific warning.
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
  [registry {ext-name :name :keys [kind emit lower trusted?] :as extension}]
  (when-not (and (string? ext-name) (seq ext-name))
    (throw (ex-info "extension name is required and must be a non-blank string"
                    {:type ::missing-extension-name :extension extension})))
  (assoc registry ext-name {:name ext-name :kind kind :emit (or emit {}) :lower lower
                            :trusted (boolean trusted?)}))

(defn register-all
  "`register` folded over `extensions` (a collection of extension maps, in
  order), starting from `registry`. A convenience for registering several
  extensions at once; later extensions in `extensions` win over earlier
  ones sharing the same `:name`, exactly as repeated `register` calls
  would."
  [registry extensions]
  (reduce register registry extensions))

(defn renderer-v2
  "Marks `f` -- a `(fn [node ctx] -> data)` renderer, TASK-89's contract,
  see `register`'s own `:emit` docstring for the full shape -- so an
  emitter's `render-directive` recognizes and calls it that way (`v2?`)
  rather than as a legacy `(fn [node target] -> string)` renderer.

  Metadata, not a wrapper value compared by identity: `with-meta` on `f`
  itself, so `f` stays exactly as callable as it always was (an aware
  consumer calls it either way; an unaware one that has never heard of
  `v2?` still gets a function it can call, just with the wrong second
  argument, the same fallback shape `haselnuss.emit.latex/
  environment-marker?`'s own docstring argues for over an inert
  sentinel) and the tag travels with the function value itself rather
  than depending on which Var happens to hold it -- the exact
  `environment-marker?`/`with-meta` precedent TASK-88 already
  established in this codebase for the identical problem (recognizing a
  special function without an identity comparison), reused here rather
  than invented twice.

  Arity alone cannot make this distinction: both signatures take two
  arguments, and a returned string cannot say after the fact whether it
  wants escaping or a verbatim splice, so which contract a given
  renderer speaks has to be declared at registration time -- this
  function is that declaration. An UNMARKED renderer is legacy by
  default, deliberately: that is what keeps every extension registered
  before TASK-89 working completely unchanged (AC #7), rather than
  silently reinterpreted as v2 and having its once-verbatim string
  escaped as plain text.

  `f` a plain function: `with-meta`-shaped (`vary-meta`, so any metadata
  `f` already carries survives alongside `::v2`), returning a new
  function value, `f` itself untouched. `f` a Var (TASK-24 review finding
  #4's own precedent for keeping a Var-backed renderer working): `Var`
  does not implement `IObj`, so `with-meta`/`vary-meta` raise a
  `ClassCastException` on one directly -- confirmed live over the nREPL
  before choosing this -- so a Var is marked via `alter-meta!` instead,
  returning `f` itself (the same Var, now carrying the tag on its own
  metadata) rather than a value `with-meta` could have produced. This is
  the one place this namespace mutates something process-global, and it
  is deliberately the narrow, benign kind doc-6 distinguishes from the
  handler-table kind it rejects elsewhere: `::v2`-ness is a static fact
  about a renderer FUNCTION, true for every caller and every document
  that ever uses it, not per-document data a handler table would have to
  keep isolated -- marking `#'my-ns/render` here no more leaks between
  documents than the Var's own `:arglists` metadata does.

  The Var check is `(instance? clojure.lang.IReference f)`, not `var?`
  (TASK-90, closing a gap TASK-89's own review flagged as forward-looking
  rather than blocking at the time): `var?` is exactly
  `(instance? clojure.lang.Var f)`, which misses `sci.lang.Var` -- the
  Var type `haselnuss.extensions.sidecar`'s SCI sandbox produces for a
  sidecar file's own `(defn ...)`, the same idiomatic shape TASK-24
  finding #4 already established for host code (`#'my-ns/render`), and
  therefore something a sidecar author would naturally reach for too.
  Confirmed live over the nREPL, for both Var types, before choosing this
  fix (mirroring how the original `var?` branch above was chosen):
  `sci.lang.Var`, exactly like `clojure.lang.Var`, does not implement
  `IObj` (so `vary-meta` throws `ClassCastException` on either) but does
  implement `IReference` (so `alter-meta!` works on either) -- `var?`
  itself is therefore a strictly narrower test than the one this function
  actually needs, and `IReference` is a strict generalization of it: every
  `clojure.lang.Var` still takes this branch exactly as before, and so
  now does a `sci.lang.Var`."
  [f]
  (if (instance? clojure.lang.IReference f)
    (do (alter-meta! f assoc ::v2 true) f)
    (vary-meta f assoc ::v2 true)))

(defn v2?
  "True when `renderer` was wrapped in `renderer-v2` -- see that
  function's own docstring for why metadata, not identity or arity, is
  the test. False for a plain function, a Var, a multimethod, or
  anything else with no `::v2` metadata of its own, which is exactly the
  legacy renderer contract's own default."
  [renderer]
  (boolean (::v2 (meta renderer))))

;; --- fragment provenance (TASK-213) ------------------------------------------
;;
;; An emitter's `Rendered` is spliced verbatim, so a v2 renderer must not be
;; able to make one with text of its own choosing. It cannot construct one
;; (`->Rendered` is not in the sandbox), but it can change one it was given:
;; `assoc`/`merge`/`into` on a record keep its type. So an emitter's
;; `render-directive` opens a fresh `issued-fragments` set per call, its ctx
;; callbacks `issue!` every `Rendered` they return into it, and an untrusted
;; renderer's result may splice only a `Rendered` that is `issued?` there --
;; by identity, since an `assoc`ed copy is a new instance. The set itself is
;; never put in the ctx a renderer sees: only the callbacks close over it.

(defn issued-fragments
  "A fresh, empty identity set of fragments issued for one renderer call.
  Synchronized: a sandboxed renderer calls its ctx callbacks on its own
  worker thread (`haselnuss.sandbox`)."
  ^java.util.Set []
  (java.util.Collections/synchronizedSet
   (java.util.Collections/newSetFromMap (java.util.IdentityHashMap.))))

(defn issue!
  "Records `fragment` in `issued` (an `issued-fragments` set, or nil for
  none) and returns `fragment`."
  [^java.util.Set issued fragment]
  (when issued (.add issued fragment))
  fragment)

(defn issued?
  "True when this very `fragment` instance was `issue!`d into `issued`."
  [^java.util.Set issued fragment]
  (boolean (and issued (.contains issued fragment))))

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

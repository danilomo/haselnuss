(ns haselnuss.extensions.sidecar
  "Loads author-defined directives from a sidecar Clojure file (TASK-90,
  doc-6's own payoff milestone): an author writes a plain `.clj` file
  defining how a directive renders to HTML and LaTeX, points `--extensions`
  at it, and the build picks it up with no recompile. `haselnuss.cli`'s own
  job is naming the CLI flags; this namespace owns evaluating the file
  safely and turning its result into `haselnuss.registry/register`-ready
  extension maps.

  SCI (Small Clojure Interpreter, `org.babashka/sci`) is the sandbox: an
  explicitly constructed evaluation environment, never the host `eval`.
  Every choice below was verified live over the nREPL against this
  project's own pinned sci version (0.12.51) before being relied on --
  doc-6 sketches the shape of this boundary; what follows is what was
  actually measured, correcting doc-6 in the one place measurement
  disagreed with it (the `:namespaces` point below), the same relationship
  doc-6 itself has to doc-3.

  THE SANDBOX (`base-context`):

  - `:classes {}` -- no host interop at all. Verified: `System`,
    `java.io.File`, `Thread` (the class), `Runtime`, `ProcessBuilder`,
    `Class` and `clojure.lang.RT` are all unresolvable, and so is `slurp`/
    `spit`/`future`/`agent`/`pmap` -- none of these need denying, because
    sci's own default core never binds them at all, `:classes {}` or not.
    Instance-method calls on a value already in hand (`(.length \"x\")`,
    `(.getClass \"x\")`) still work regardless of `:classes` -- it gates
    NEW class/static/constructor resolution, not dispatch on an object a
    renderer already holds -- but the classic escalation from there
    (`.getMethod`/`.getDeclaredMethod`/`.forName`/`.newInstance`/
    `.invoke`/`.getClassLoader`, the reflection chain that would otherwise
    turn \"I can call a harmless method on a String\" into \"I can load and
    run arbitrary bytecode\") is separately and unconditionally blocked by
    sci's OWN hardcoded method denylist (`\"Method X on class Y not
    allowed!\"`), independent of `:classes` -- verified live, all six.
  - `:deny '[eval resolve intern alter-var-root load-string]` -- doc-6's
    own list. All five are present in sci's default core and, verified
    live, already stay sandboxed rather than reaching the host even
    undenied (`(eval '(System/exit 0))` inside sci still fails to resolve
    `System/exit`, since sci's own `eval` recurses through itself, not the
    host compiler) -- denying them anyway is deliberate defense in depth
    matching doc-6's own recorded decision, not a response to a live
    escape found.
  - `:load-fn (fn [_] nil)` -- explicit, per doc-6, though sci's own
    default already fails closed for a namespace it does not already
    know about. Verified this blocks `require` of real namespaces on
    THIS project's own classpath (`haselnuss.parser`, `clojure.java.shell`,
    `clojure.java.io`). Honest gap, not glossed over: sci ships its own
    bundled pure-Clojure ports of a few \"extra\" namespaces (confirmed:
    `clojure.set`) that stay requireable regardless of `:load-fn`/
    `:namespaces`, since they are never loaded through that callback at
    all -- harmless for this threat model (no IO/interop/threading in
    them) but not literally \"nothing reachable via require\", so it is
    named here rather than assumed away.
  - `:namespaces {'haselnuss.ext {'raw ...}}` -- adds exactly one extra
    namespace, one helper (`raw`, building the `[:raw s]` node both
    emitters' `RenderData` vocabularies already use for the trust-gated
    verbatim escape hatch -- see `haselnuss.registry/register`'s own
    `:emit` docstring). Deliberately the only thing in it: the AST is
    plain data (doc-2/doc-3's own thesis), so building hiccup for HTML or
    an escaped-text/fragment for LaTeX, or a `:lower` rule's own output
    Blocks, needs no functions at all -- ordinary map/vector literals
    already suffice, and this namespace does not invent an API the
    worked example (or anything else) would need to exercise.

    This key is NOT what keeps `clojure.core` small, and does not claim
    to be, correcting doc-6's own \"curated subset of clojure.core\"
    phrasing against what was actually measured: passing `:namespaces
    {'clojure.core {...a small hand-picked map...}}}` does not replace or
    shrink sci's own default `clojure.core` -- verified live with two
    different symbols absent from such a map (`+`, `dec`), both still
    reachable regardless -- `:namespaces` only ever ADDS bindings on top
    of the default. So `clojure.core`'s own safety here is `:classes {}`
    plus `:deny` plus sci's already-conservative default core (verified
    free of IO/interop/concurrency above), not a hand-curated allowlist
    sci's own merge semantics would not actually enforce.
  - `haselnuss.registry` is NOT on this list, deliberately: every
    sidecar-authored `:emit`/`:lower` function is wrapped in
    `registry/renderer-v2` (there is no reason to offer the legacy
    string-returning contract to brand-new code) and given its `:trusted`
    value HOST-SIDE, by this namespace, after being extracted from the
    sandbox -- never by code running inside it. A sidecar file therefore
    cannot mark its own output trusted or call `renderer-v2` itself; both
    are decisions this namespace and `haselnuss.cli`'s own
    `--trusted-extensions` flag make on its behalf.

  RUNTIME BOUNDS, layered (`sci-call!`), and sharper than doc-6 itself
  anticipated: sci 0.12.51 ships NO interpreter-level interrupt mechanism
  at all (confirmed against its own source: nothing in the jar matches
  \"interrupt\"), and a raw `Thread/interrupt` on the evaluating thread
  stops NEITHER a purely-interpreted infinite loop NOR a single host call
  like an unbounded `reduce` -- both left the worker thread alive across a
  generous wait, verified live. So every sandboxed call this namespace
  makes -- loading a sidecar file's own top-level forms, and every later
  call to a renderer/lower rule it defines -- runs on a fresh daemon
  thread under an external wall-clock deadline; on timeout, `.interrupt`
  is still attempted (harmless, and forward-compatible if a future sci
  release adds cooperative checking) but the caller gets a timeout error
  and the build proceeds/fails promptly regardless of whether the worker
  actually stops. The worker itself may keep a CPU core busy indefinitely
  for genuinely non-terminating code -- an honestly-documented residual,
  not something this namespace claims to close (see \"What this does not
  cover\" below) -- but `:daemon true` is what keeps it from being
  unbounded in the sense that matters for a CLI process: it cannot outlive
  the JVM, so `-main`'s own exit (or a test JVM's eventual one) reclaims
  it. `sci/out`/`sci/err` are rebound to fresh `StringWriter`s for the
  duration of every such call (verified: this has to happen INSIDE the
  worker thread's own body, since dynamic-var bindings are thread-local
  and do not propagate to a freshly spawned `Thread`) and simply
  discarded -- captured rather than mixed into the build's own output, no
  more. A successful call's own return value is size-capped via
  `(count (pr-str result))`. A LATER call into a renderer/lower rule this
  namespace already extracted from the sandbox also re-establishes
  `sci.ctx-store`'s own current-context binding for its duration
  (`wrap-fn`'s own docstring has the full story) -- found live, not
  anticipated: sci's `require` reaches for it directly, and without it a
  renderer's own otherwise-correct `require` call fails with sci's
  internal \"no context found\" error instead of the graceful \"could not
  find namespace\" refusal AC #6 is actually about.

  ISOLATION: one `sci/fork` off ONE `sci/init`-built base context (built
  lazily, `delay`-wrapped, mirroring `haselnuss.cli/version`'s own \"pay
  the cost only if used\" idiom) per call to `load-extensions` -- i.e. per
  document build -- shared by every `--extensions` path in that one build,
  never reused across builds. Verified live: a `def` made in one fork is
  invisible both to a SIBLING fork of the same base and to a later, FRESH
  fork of that same base, so nothing one document's sidecar file defines
  outlives or leaks into a later, unrelated conversion in the same
  process.

  THE SIDECAR CONTRACT: a file's own LAST top-level form must evaluate to
  a map, or a COLLECTION of maps (a vector, list, lazy seq -- what
  `for`/`map`/`filter` return, and the natural way to define several
  directives at once -- or a set; `extension-collection?` matches
  `haselnuss.registry/register-all`'s own genuine tolerance rather than
  being narrower than it), each in `haselnuss.registry/register`'s own
  `{:name :kind? :emit? :lower?}` shape (the same shape
  `haselnuss.extensions.collapsable/extension` already is) -- an author
  who has read that namespace's docstring, or copied that extension map,
  already knows this shape. `:emit`'s per-target values and `:lower`
  (when present) are plain functions; see `load-extensions` for the exact
  validation and wrapping.

  WHAT THIS DOES NOT COVER (doc-6's own honesty requirement, restated
  here because this is the namespace it applies to): this sandboxes an
  AUTHOR RUNNING THEIR OWN SIDECAR FILE, where the goal is preventing
  accident and casual mischief -- a typo that tries to read a file, a
  copy-pasted loop that never terminates, a stray `println` left in from
  debugging. It does NOT withstand a determined attacker. A single
  runaway host call inside sandboxed code is not reliably bounded by
  anything here (verified above); sci's own reflection denylist and this
  namespace's `:classes {}`/`:deny` close every concrete escape this task
  tested, but neither this namespace nor sci's own documentation claims
  that list is exhaustive against a deliberately adversarial input.
  Withstanding one needs a process boundary that can be killed from
  outside the JVM entirely, which is out of scope here -- the thing to
  add if the threat model ever changes from \"an author's own document\"
  to \"a hosted service converting documents from strangers\".

  A WARNING FOR ANYONE ITERATING ON THIS CODE VIA THE NREPL: the abandoned-
  worker-thread residual above is not only a production/build concern.
  `lein test` is safe -- it forks a fresh JVM per invocation, so the
  worker thread `ac7-non-terminating-code-stopped-test`'s own infinite
  loop leaks dies with that JVM when the run ends. The persistent, shared
  dev nREPL this project's own README/CLAUDE.md points people to for fast
  feedback is NOT: evaluating an AC #7-shaped non-terminating call there
  directly -- the natural way to explore or extend this namespace
  interactively -- leaves a CPU-pegged daemon thread running in that
  SAME, SHARED process until it is restarted, degrading it for every
  later evaluation and every other engineer or agent using it in the
  meantime (confirmed happening six times over the course of this
  namespace's own development, each one requiring an nREPL restart to
  clear). Reproduce a non-terminating scenario via `lein test`, or a
  throwaway `lein run -m clojure.main` process, never the pinned
  `127.0.0.1:7888` one."
  (:require [clojure.java.io :as io]
            [haselnuss.registry :as registry]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store]))

(def ^:private default-timeout-ms
  "How long, in wall-clock milliseconds, one sandboxed call (loading a
  sidecar file's own top-level forms, or one later call to a renderer/
  lower rule it defines) is allowed to run before `sci-call!` gives up on
  it -- generous enough for real rendering work, short enough that a
  build using this feature does not sit hung on a mistake. See this
  namespace's own docstring for why this bound has to be external rather
  than relying on anything inside sci itself."
  5000)

(def ^:private default-output-limit
  "The cap, in `(count (pr-str result))` characters, on what one
  sandboxed call may successfully return (AC #8) -- generous for any
  plausible single directive's own rendering (hiccup for one element, a
  handful of TeX commands), small enough to catch a runaway
  string-building bug rather than let it reach the emitter and, from
  there, the written output file."
  200000)

(def ^:private ext-namespace
  "The one extra namespace this sandbox adds on top of sci's own default
  core (see this namespace's own docstring, `:namespaces`, for why it
  stays this small): `raw`, building the `[:raw s]` node both emitters'
  `RenderData` vocabularies use for the trust-gated verbatim escape
  hatch. Whether it is HONORED is still decided entirely host-side, by
  `haselnuss.registry/register`'s own `:trusted` (`load-extensions`
  forces this from `--trusted-extensions`, never from the sidecar file
  itself) -- calling this constructor never grants trust by itself, only
  shapes the node an untrusted renderer's own attempt is refused for.
  `(str s)`, not `s` verbatim: matches this namespace's own recorded plan
  (which this shipped as `[:raw s]` without, an unremarked drift caught by
  review). A no-op for the common case, and harmless either way even for
  an uncommon one: `RenderData`'s own `:raw` branch (`haselnuss.emit.html`/
  `latex`'s own `render-data`) splices `(second v)` through unchanged, but
  the surrounding `render-blocks` -- `(str html block-html)` in
  `haselnuss.emit.html`, `(str/join \"\\n\\n\" ...)` in `haselnuss.emit.
  latex` -- concatenates it into the rest of the document via plain `str`
  regardless, so a non-string second element was always going to be
  coerced sooner or later; coercing it here just makes that explicit
  rather than incidental."
  {'raw (fn [s] [:raw (str s)])})

(def ^:private base-context
  "One locked sci context, built once, the first time anything actually
  needs it (`haselnuss.cli/version`'s own `delay` idiom, for the same
  reason: a build that passes no `--extensions` flag at all should not
  pay sci's own init cost). Never evaluates anything directly -- every
  real use is a fresh `sci/fork` of this, one per document build, so nothing
  a fork's own evaluation defines is visible here or in a sibling fork.
  See this namespace's own docstring for the full reasoning behind each
  option below."
  (delay
    (sci/init {:namespaces {'haselnuss.ext ext-namespace}
               :classes {}
               :deny '[eval resolve intern alter-var-root load-string]
               :load-fn (fn [_] nil)})))

(def ^:private worker-thread-prefix
  "The fixed prefix every worker thread `bounded-eval!` spawns carries in
  its own name (`worker-thread`), ahead of that specific call's own
  `describe` label -- found worth doing live, not merely anticipated: an
  abandoned worker (see that function's own docstring on why one can
  outlive its own timeout) showed up in `top -H`/`jstack` as a bare,
  unhelpful `Thread-N` before this, with nothing distinguishing it from
  any other thread in the process or naming which extension call it came
  from -- exactly the identifying information someone debugging a
  CPU-pegged process via those tools needs first."
  "haselnuss-sidecar-worker: ")

(defn- worker-thread
  "A daemon `Thread`, named `worker-thread-prefix` followed by `describe`
  (`sci-call!`'s own label for this specific call -- a path, or a
  directive/target pair -- so an abandoned worker is identifiable in
  `top -H`/`jstack` rather than a bare `Thread-N`), running
  `(deliver p ...)` from `thunk`'s own result -- `{:ok value}` on a normal
  return, `{:error throwable}` on an exception -- never left running with
  anything blocking the caller once the promise is delivered. `:daemon
  true` is what keeps a thread this function abandons on timeout (see
  `bounded-eval!`) from being able to outlive the JVM process itself."
  [thunk describe p]
  (doto (Thread. ^Runnable
         (fn []
           (try
             (deliver p {:ok (thunk)})
             (catch Throwable t (deliver p {:error t}))))
                 ^String (str worker-thread-prefix describe))
    (.setDaemon true)))

(defn- bounded-eval!
  "Runs `thunk` on a fresh worker thread named after `describe`
  (`worker-thread`), bounded by `timeout-ms` wall-clock milliseconds --
  the external half of this namespace's own layered resource bound (its
  own docstring has the full reasoning for why this cannot be delegated
  to anything inside sci itself). Returns `{:ok value}`, `{:error
  throwable}`, or `{:timeout true}`.

  On timeout, `.interrupt` is still called on the abandoned worker -- cheap,
  harmless, and forward-compatible if sci ever adds a cooperative check of
  its own -- but this function does not wait to find out whether it had any
  effect: the caller gets `{:timeout true}` immediately, which is what
  keeps THIS build from hanging regardless of whether that worker thread
  ever actually stops."
  [thunk describe timeout-ms]
  (let [p (promise)
        worker (worker-thread thunk describe p)]
    (.start worker)
    (let [result (deref p timeout-ms ::timeout)]
      (if (= ::timeout result)
        (do (.interrupt worker) {:timeout true})
        result))))

(defn- output-size
  "The size `check-output-size!` measures a successful call's own return
  value by: the length of its `pr-str` representation, a single measure
  that catches both \"one huge string\" and \"one huge nested structure\"
  without walking the value's own shape by hand."
  [value]
  (count (pr-str value)))

(defn- force-realized
  "`value`, with every element of it forced now if it is lazy (`seq?` --
  what a `for`/`map`/`filter` call returns, among others), rather than
  left for a caller to realize later, outside `sci-call!`'s own timeout/
  capture bound -- widening `extension-entries` to accept exactly this
  shape (a sidecar file's last form returning `(for [n [...]] {...})`,
  the natural way to define several directives at once, rather than
  requiring `vec`) would otherwise open a real gap: `every? map?`, the
  check that shape needs, forces a lazy seq's elements as a side effect,
  but only where it is CALLED, which is after `sci-call!` has already
  returned -- outside the timeout this whole namespace exists to enforce,
  and outside the `sci/binding` scope capturing whatever printing
  realizing those elements might do.

  Called from INSIDE `sci-call!`'s own timed, output-capturing thunk
  instead -- and, load-bearingly, nested INSIDE `sci/binding`'s own
  dynamic extent there, as `(sci/binding [...] (force-realized (f)))`,
  never `(force-realized (sci/binding [...] (f)))`. The two look
  equivalent but are not: `sci/binding` (like `binding`) only holds its
  dynamic scope open for the duration of evaluating its OWN body, and
  `(f)` alone is that body in the second, wrong form -- so `(f)`'s
  potentially-still-lazy return value comes back out, the binding's scope
  closes, and only THEN does `force-realized` walk it, with `sci/out`/
  `sci/err` already unbound (confirmed live: a lazy seq whose own
  realization calls back into interpreted code that prints -- exactly
  what `extension-collection?` widening this file's own
  `ac1-lazy-seq-of-extensions-test` exercises -- threw sci's internal
  `ClassCastException: class sci.impl.vars.SciUnbound cannot be cast to
  class java.io.Writer` instead of loading cleanly, a real regression a
  round of review caught). Putting `(force-realized (f))` INSIDE
  `sci/binding`'s own body closes this: realization now happens while the
  dynamic scope is still open, so a lazy seq is bounded by the timeout
  (`bounded-eval!` wraps the whole thunk regardless) AND captured by the
  output redirect, both properties this function's callers rely on
  actually holding together.

  `doall` on anything that is not `seq?` (a map, a string, a hiccup
  vector, a bare keyword) is never attempted -- `seq?` is false for all of
  those, so this is a safe no-op for the overwhelmingly common case, not
  merely a redundant one.

  Deliberately SHALLOW, not a recursive walk: only `value` ITSELF is
  forced, not a lazy seq nested two or more levels inside an otherwise-
  eager structure (`[:div {} (map render-child items)]`'s own children,
  say). That is enough for both of this function's own callers --
  `extension-entries` needs exactly `value`'s own top level realized to
  validate it (every element, once reached, must itself already be a
  map, never a further lazy computation to force), and a v2 renderer's
  hiccup/TeX-node vocabulary is walked by the HOST emitter afterward
  (`haselnuss.emit.html`/`latex`'s own `render-data`), which forces
  whatever it finds as an ordinary side effect of rendering it -- so
  nothing here needs to pre-force a structure something else is about to
  walk anyway. Named plainly as a boundary, not silently assumed: THAT
  later, host-side forcing happens outside this namespace's own timeout/
  capture bound, the same class of gap this function exists to close at
  the TOP level -- a renderer returning something whose laziness is
  nested rather than at the surface is not protected by this function,
  only by whatever the emitter's own walk happens to force promptly.
  Recursively walking an arbitrary, author-supplied structure to close
  that completely would be a materially bigger, riskier change (walking
  something arbitrarily deep or self-referential) for a gap no review of
  this namespace has reproduced live, unlike the two nesting-order
  mistakes this same function's own history already found -- left as a
  known, bounded residual rather than solved speculatively here."
  [value]
  (if (seq? value) (doall value) value))

(defn- sci-call!
  "The one protected-call primitive every sandboxed evaluation in this
  namespace goes through -- loading a sidecar file's own top-level forms,
  and every later call to a renderer/lower rule it defines. Calls `(f)`
  under this namespace's own runtime bounds, in the order they take
  effect (see this namespace's own docstring for why each one is here and
  what it does and does not cover):

  1. `sci/out`/`sci/err` rebound to fresh, per-call `StringWriter`s (AC
     #9) -- established INSIDE the worker thread `bounded-eval!` spawns,
     since `sci/binding` is thread-local and does not propagate to a
     freshly spawned `Thread`. Captured text is discarded; there is no
     diagnostic surfaced for it today (AC #9 asks only that it not reach
     this build's own output).
  2. `force-realized`, called on `(f)`'s result FROM INSIDE that same
     `sci/binding` form's own body -- `(sci/binding [...] (force-realized
     (f)))`, not `(force-realized (sci/binding [...] (f)))`, which looks
     equivalent but is not (`force-realized`'s own docstring has the full
     story, including the exact `ClassCastException` a round of review
     caught this shipping as) -- so a lazy return value
     (`extension-entries`' own widened acceptance of a `for`/`map`
     result, not only a literal vector) is fully forced here, under the
     SAME bound AND the same output capture as everything else, rather
     than by whatever later code first walks it.
  3. `bounded-eval!`'s own wall-clock deadline (AC #7), which the
     realization step above is inside of, not exempt from.
  4. `check-size?` (default true): `output-size`, folded into this
     function so a call that TIMES OUT while still trying to compute an
     oversized value reports as a timeout, not a separate size failure.
     `load-extensions` passes `false` for the one call that loads a
     sidecar file's own top-level forms -- AC #8 is about a RENDERER's
     own returned output, not the extension-spec MAP that defines it,
     which necessarily embeds opaque, memory-address-bearing function
     `pr-str`s of no meaningful \"size\" at all (found live: a trivial
     extension map's own `pr-str` already sits within a few characters of
     a deliberately small test cap, for reasons entirely unrelated to
     anything an author wrote) -- every actual renderer/lower-rule CALL
     still goes through this check via `wrap-fn`.

  `describe` is a short label (a path, a directive name, ...) `fail!`
  puts in every error this raises, so a build failure this namespace
  causes always names where it came from (AC #10's own naming
  requirement, applied uniformly rather than only to shape validation),
  and (`bounded-eval!`/`worker-thread`) what an abandoned worker's own
  thread name says it was doing. `opts` -- `:timeout-ms`/`:output-limit`,
  both defaulting to this namespace's own `default-timeout-ms`/
  `default-output-limit`."
  ([f describe opts] (sci-call! f describe opts true))
  ([f describe {:keys [timeout-ms output-limit]
                :or {timeout-ms default-timeout-ms output-limit default-output-limit}}
    check-size?]
   (let [out (java.io.StringWriter.)
         err (java.io.StringWriter.)
         result (bounded-eval! (fn [] (sci/binding [sci/out out sci/err err] (force-realized (f))))
                               describe timeout-ms)]
     (cond
       (:timeout result)
       (throw (ex-info (str describe " did not finish within " timeout-ms "ms")
                       {:type ::extension-timeout :describe describe :timeout-ms timeout-ms}))

       (:error result)
       (throw (ex-info (str describe " raised an error: " (ex-message (:error result)))
                       {:type ::extension-evaluation-error :describe describe}
                       (:error result)))

       :else
       (let [value (:ok result)
             size (and check-size? (output-size value))]
         (when (and check-size? (> size output-limit))
           (throw (ex-info (str describe "'s own output is " size
                                " characters, over this build's " output-limit "-character cap")
                           {:type ::oversized-extension-output :describe describe
                            :size size :limit output-limit})))
         value)))))

(defn- wrap-fn
  "`f` -- an already-extracted function value from inside the sandbox (a
  sidecar's own `:emit` renderer, or its own `:lower` rule) -- adapted
  into an ordinary host function of the same arity that runs every call
  to it through `sci-call!` (AC #7/#8/#9 apply to a LATER call exactly as
  much as to loading the file that defined it, since the non-terminating
  or oversized-output code could just as easily live inside the function
  body, only reached once the emitter/lower pass actually calls it for a
  matching directive). `describe` and `opts` are `sci-call!`'s own.

  Also re-establishes `sci.ctx-store`'s own context for the duration of
  the call (`ctx-store/with-ctx`) -- found live, not anticipated: sci's
  `require` implementation (`sci/impl/load.cljc`) reaches for \"the
  current context\" through that store rather than through anything
  `sci/binding` covers, and a context extracted here and called LATER,
  outside `sci/eval-string*`'s own dynamic extent (exactly this
  function's whole job), has none -- confirmed live: an otherwise-correct
  `require` call from an extracted function throws sci's own
  `\"No context found in: sci.ctx-store/*ctx*\"` without this, and the
  intended, graceful `\"Could not find namespace\"` refusal (AC #6) with
  it. `ctx-store`'s own docstring says as much: \"SCI does not populate
  this dynamic var itself during evaluation... projects that need to
  expose their context to functions\" do it themselves -- this is that.

  `force-realized` is called TWICE for this thunk, deliberately, not by
  oversight: once here, on `(apply f args)`'s own result, still INSIDE
  `with-ctx`'s own dynamic extent; once more inside `sci-call!` itself, on
  the whole thunk's return value, inside ITS OWN `sci/binding` extent
  (`sci-call!`'s own docstring). `sci-call!` has no way to know that ITS
  caller's thunk establishes a FURTHER dynamic scope of its own -- it can
  only guarantee realization happens before ITS OWN scope closes, not
  before every scope a more deeply-nested thunk might open and close
  first. A renderer/lower rule returning a lazy value (a `(map ...)`
  fragment, say) whose OWN realization needs `ctx-store/*ctx*` (calls
  `require`, exactly like the file-load case `extension-collection?`
  widening motivated `force-realized` for in the first place) would
  otherwise hit the identical `\"No context found\"` error one level
  deeper, once `with-ctx`'s own scope has already closed but before
  `sci-call!`'s realizes it -- confirmed live before choosing this. The
  second call, in `sci-call!`, is then a safe no-op over an
  already-realized (cached) seq for this call shape specifically, kept
  rather than removed so `sci-call!`'s own guarantee stays
  unconditionally true for ANY thunk, including a future one that opens
  no scope of its own at all."
  [f ctx describe opts]
  (fn [& args]
    (sci-call! #(ctx-store/with-ctx ctx (force-realized (apply f args))) describe opts)))

(defn- blank-name?
  [x]
  (not (and (string? x) (seq x))))

(defn- malformed!
  [path reason data]
  (throw (ex-info (str "malformed extension in " path ": " reason)
                  (merge {:type ::malformed-extension :path path} data))))

(defn- validate-entry!
  "Throws a `::malformed-extension` `ex-info` naming both `path` and what
  is wrong with extension-spec map `entry` (AC #10), or returns `entry`
  unchanged. Deliberately as lenient as `haselnuss.registry/register`
  itself about anything beyond `:name`/`:emit`/`:lower` -- an unknown key
  passes through untouched, exactly as `register` already tolerates,
  rather than this namespace maintaining a second, stricter schema for
  the same shape."
  [path entry]
  (when-not (map? entry)
    (malformed! path (str "expected an extension map, got " (pr-str entry)) {:value entry}))
  (when (blank-name? (:name entry))
    (malformed! path (str "extension is missing a non-blank :name: " (pr-str entry)) {:value entry}))
  (let [emit (:emit entry)]
    (when (some? emit)
      (when-not (map? emit)
        (malformed! path (str "extension " (pr-str (:name entry)) "'s :emit must be a map "
                              "of target keyword to a rendering function, got " (pr-str emit))
                    {:name (:name entry) :value emit}))
      (doseq [[target f] emit]
        (when-not (ifn? f)
          (malformed! path (str "extension " (pr-str (:name entry)) "'s :emit " (pr-str target)
                                " is not a function: " (pr-str f))
                      {:name (:name entry) :target target :value f})))))
  (when (and (some? (:lower entry)) (not (ifn? (:lower entry))))
    (malformed! path (str "extension " (pr-str (:name entry)) "'s :lower is not a function: "
                          (pr-str (:lower entry)))
                {:name (:name entry) :value (:lower entry)}))
  entry)

(defn- normalize-entry
  "Validated extension-spec map `entry` (from a sidecar file, `path`'s own
  source), turned into a `haselnuss.registry/register`-ready extension map:
  every `:emit` target function and the `:lower` function (when present)
  wrapped via `wrap-fn` so a LATER call into either goes through the same
  bound `sci-call!` applies to loading the file itself; every `:emit`
  function additionally marked `registry/renderer-v2` (every sidecar-
  authored renderer speaks TASK-89's v2 contract only -- there is no
  legacy string-returning renderer a brand-new sidecar file could have
  predated); `:trusted`/`:trusted?`, if the entry carried either, is
  DROPPED and replaced with `trusted?` -- this call's own, CLI-decided
  value, never the sidecar file's own -- which is what keeps a sidecar
  from marking its own output trusted (AC #11). `:kind`, if present,
  passes through untouched: it is plain data (a `LabelLexicon`
  merge-fragment, `register`'s own docstring), nothing to wrap. `ctx` is
  `wrap-fn`'s own -- the forked sci context this entry's functions were
  extracted from, re-established around every later call."
  [entry path ctx trusted? opts]
  (let [describe-for (fn [suffix] (str "extension " (pr-str (:name entry)) " (" path ")" suffix))]
    (-> entry
        (dissoc :trusted :trusted?)
        (assoc :trusted? trusted?)
        (cond->
         (:emit entry)
          (assoc :emit (into {}
                             (map (fn [[target f]]
                                    [target (registry/renderer-v2
                                             (wrap-fn f ctx
                                                      (describe-for (str "'s :" (name target)
                                                                         " renderer"))
                                                      opts))]))
                             (:emit entry)))
          (:lower entry)
          (assoc :lower (wrap-fn (:lower entry) ctx (describe-for "'s :lower rule") opts))))))

(defn- extension-collection?
  "True when `value` is a plain Clojure collection of extension maps,
  distinct from a single extension map itself (`extension-entries`'s own
  first branch already handles that case): a vector, a list, a lazy seq
  -- what `for`/`map`/`filter` naturally return, and the single most
  idiomatic way an author reaches for \"define several directives at
  once\" -- or a set. Matches `haselnuss.registry/register-all`'s own
  genuine tolerance (`(reduce register registry extensions)`, which walks
  any seqable collection with no preference for `vector?` specifically --
  its own docstring already calls its argument only \"a collection of
  extension maps\") rather than being narrower than the function that
  actually consumes this namespace's own output (found by review: a
  literal `vector?` check rejected `(for [n [...]] {...})`, forcing an
  author to remember an unnecessary `vec`). `sequential?` alone would miss
  a set -- `clojure.lang.PersistentHashSet` does not implement
  `clojure.lang.Sequential` -- so it is checked separately."
  [value]
  (and (or (sequential? value) (set? value)) (every? map? value)))

(defn- extension-entries
  "The extension-spec maps `value` -- a sidecar file's own final top-level
  form -- must be: `value` itself if it is a map, `value` (realized into a
  vector) if it is an `extension-collection?` of maps, else a
  `::malformed-extension` naming `path` (AC #10). A single map is the
  common case (one directive per file); a collection is for a file
  defining several at once.

  `value` reaching here has already been through `force-realized` (inside
  `sci-call!`, before this function is ever called), so `(vec value)` for
  a lazy seq is realizing an already-forced, cached seq -- cheap, not a
  second pass over unbounded, uncaptured computation."
  [value path]
  (cond
    (map? value) [value]
    (extension-collection? value) (vec value)
    :else (malformed! path
                      (str "a sidecar file's last form must evaluate to an extension map "
                           "or a collection of them (a vector, list, lazy seq, or set), got "
                           (pr-str value))
                      {:value value})))

(defn load-extensions
  "Loads every sidecar file named in `paths` (in order), returning a
  vector of `haselnuss.registry/register`-ready extension maps --
  `haselnuss.cli`'s own `register-all` is what actually installs them.

  `opts`: `:trusted?` (default false -- AC #11, forced onto EVERY
  extension every one of `paths` defines, regardless of what the file's
  own map says; see `normalize-entry`), `:timeout-ms`/`:output-limit`
  (`sci-call!`'s own bounds, both defaulting to this namespace's own
  constants).

  All of `paths` share ONE `sci/fork` for this one call -- built fresh
  here, never reused across a later call -- so files loaded together may
  reference each other's `def`s, and so this call's own isolation
  guarantee (this namespace's own docstring, AC #2) holds for the whole
  set at once, not file by file.

  A `path` naming no file is a hard `ex-info` (`::missing-extension-file`)
  naming it, mirroring `haselnuss.cli/load-overlays!`'s own established
  convention for the identical situation. A file whose own evaluation
  times out, errors, or returns something that does not match this
  namespace's own extension-spec shape is `sci-call!`/`validate-entry!`'s
  own `ex-info`, already naming `path` (AC #10)."
  ([paths] (load-extensions paths {}))
  ([paths {:keys [trusted?] :or {trusted? false} :as opts}]
   (let [ctx (delay (sci/fork @base-context))]
     (into []
           (mapcat (fn [path]
                     (let [file (io/file path)]
                       (when-not (.isFile file)
                         (throw (ex-info (str "extension file not found: " path)
                                         {:type ::missing-extension-file :path path})))
                       (let [source (slurp file)
                             value (sci-call! #(sci/eval-string* @ctx source)
                                              (str "extension file " path) opts false)]
                         (->> (extension-entries value path)
                              (map (fn [entry]
                                     (normalize-entry (validate-entry! path entry)
                                                      path @ctx trusted? opts))))))))
           paths))))

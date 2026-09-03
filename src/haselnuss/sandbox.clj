(ns haselnuss.sandbox
  "The reusable SCI (Small Clojure Interpreter, `org.babashka/sci`) sandbox
  behind BOTH `--extensions` (`haselnuss.extensions.sidecar`, TASK-90) and
  `--transform` (`haselnuss.extensions.rules`, TASK-103): one locked base
  context, one `sci-call!` protected-call primitive and one `wrap-fn`
  call-time re-binder, generalized out of `haselnuss.extensions.sidecar`
  (where this all used to live, shaped around registry entries only) rather
  than copied for a second, independent sidecar-file loader -- 'two
  independent sandboxes with two independent policies is how a sandbox
  stops being one' (doc-7 decision 5). Everything below is policy-neutral:
  it knows nothing about `haselnuss.registry`'s `{:name :emit :lower}`
  shape or `haselnuss.transform`'s `{:select :transform}` one -- that
  validation and normalization stays in each loader, the one thing genuinely
  specific to what its own sidecar file's last form is supposed to mean.

  Every choice below was verified live over this project's own pinned sci
  version (0.12.51) before being relied on, when this code still lived in
  `haselnuss.extensions.sidecar` -- that namespace's own git history has the
  full record of what was measured and why; nothing about moving the code
  here re-verifies or changes any of it.

  THE SANDBOX (`base-context`):

  - `:classes {}` -- no host interop at all: `System`, `java.io.File`,
    `Thread`, `Runtime`, `ProcessBuilder`, `Class` and `clojure.lang.RT` are
    all unresolvable, and neither is `slurp`/`spit`/`future`/`agent`/`pmap`
    -- sci's own default core never binds any of those regardless of
    `:classes`. An instance-method call on a value already in hand
    (`(.length \"x\")`) still works -- `:classes` gates new class/static/
    constructor resolution, not dispatch on an object already held -- but
    the reflection chain that would otherwise turn that into arbitrary
    bytecode execution (`.getMethod`/`.forName`/`.newInstance`/...) is
    separately and unconditionally blocked by sci's own hardcoded method
    denylist, independent of `:classes`.
  - `:deny '[eval resolve intern alter-var-root load-string]` -- defense in
    depth: all five already stay sandboxed rather than reaching the host
    even undenied (sci's own `eval` recurses through itself, never the host
    compiler), so denying them anyway is deliberate, not a response to a
    live escape found.
  - `:load-fn (fn [_] nil)` -- blocks `require` of any real namespace on
    this project's own classpath (`haselnuss.parser`, `clojure.java.shell`,
    `clojure.java.io`, ...). Honest gap, not glossed over: sci ships its own
    bundled pure-Clojure ports of a few \"extra\" namespaces (`clojure.set`
    confirmed) that stay requireable regardless, since they are never loaded
    through this callback at all -- harmless for this threat model (no IO/
    interop/threading in them), but not literally \"nothing reachable via
    require\".
  - `:namespaces {'haselnuss.ext ...}` -- the one extra namespace both
    loaders' sandboxes add on top of sci's own default core: every public
    var of `haselnuss.ext` (TASK-90's `raw`, TASK-103's `text`/`map-text`/
    `text->inlines`), bound by reference to the REAL, ordinary Clojure
    functions that namespace defines -- there is no second, sandbox-only
    copy of any of that logic.
  - `haselnuss.registry` is NOT on this list, deliberately, and never will
    be: every sidecar-authored `:emit`/`:lower` function is wrapped in
    `registry/renderer-v2` and given its `:trusted?` value HOST-SIDE, by
    `haselnuss.extensions.sidecar`, after being extracted from the sandbox
    -- never by code running inside it, and this namespace does not expose
    a way for that to change.

  RUNTIME BOUNDS, layered (`sci-call!`): sci 0.12.51 ships no interpreter-
  level interrupt mechanism at all, and a raw `Thread/interrupt` on the
  evaluating thread stops neither a purely-interpreted infinite loop nor a
  single unbounded host call. So every sandboxed call this namespace makes
  -- loading a sidecar file's own top-level forms, and every LATER call to
  a renderer/lower rule or transform function it defines -- runs on a fresh
  daemon thread under an external wall-clock deadline (`bounded-eval!`); on
  timeout, `.interrupt` is still attempted but the caller gets a timeout
  error immediately regardless of whether the worker actually stops.
  `:daemon true` is what keeps such a worker from outliving the JVM
  process, not from pegging a CPU core indefinitely -- an honestly
  documented residual, not something this namespace claims to close (see
  \"What this does not cover\" below). `sci/out`/`sci/err` are rebound to
  fresh `StringWriter`s for the duration of every call, established INSIDE
  the worker thread's own body (dynamic-var bindings are thread-local and
  do not propagate to a freshly spawned `Thread`), and simply discarded. A
  successful call's own return value is size-capped via `(count (pr-str
  result))`. A LATER call into a function this namespace already extracted
  from the sandbox (`wrap-fn`) also re-establishes `sci.ctx-store`'s own
  current-context binding for its duration -- sci's `require` reaches for
  it directly, and without it a call's own otherwise-correct `require`
  fails with sci's internal \"no context found\" error instead of the
  graceful \"could not find namespace\" refusal the sandbox is actually
  about.

  ISOLATION: `fork` builds one `sci/fork` off ONE `sci/init`-built
  `base-context` (built lazily, `delay`-wrapped, so a build using neither
  `--extensions` nor `--transform` pays no sci init cost at all). Callers
  -- `haselnuss.extensions.sidecar`/`rules` -- fork once per document
  build, and the two loaders SHARE that one fork within a single build
  (rather than each forking independently) when a caller (`haselnuss.cli`)
  passes the same forked context to both: a `def` made in one fork is
  invisible both to a sibling fork of the same base and to a later, fresh
  fork of that same base, so nothing one document's sidecar file(s) define
  outlives or leaks into a later, unrelated conversion in the same
  process -- and, symmetrically, an `--extensions` file and a `--transform`
  file loaded in the SAME build, sharing the same fork, may reference each
  other's top-level `def`s, the same way two `--extensions` files sharing
  one fork already can.

  WHAT THIS DOES NOT COVER (doc-6's own honesty requirement, restated here
  because this is the namespace it applies to): this sandboxes an AUTHOR
  RUNNING THEIR OWN SIDECAR FILE, where the goal is preventing accident and
  casual mischief, not withstanding a determined attacker. A single
  runaway host call inside sandboxed code is not reliably bounded by
  anything here; sci's own reflection denylist and this namespace's
  `:classes {}`/`:deny` close every concrete escape ever tested against
  this code, but neither claims that list is exhaustive against a
  deliberately adversarial input. Withstanding one needs a process boundary
  that can be killed from outside the JVM entirely, out of scope here.

  A WARNING FOR ANYONE ITERATING ON THIS CODE VIA THE NREPL: the abandoned-
  worker-thread residual above is not only a production/build concern. The
  persistent, shared dev nREPL this project's own README/CLAUDE.md points
  people to for fast feedback is NOT process-isolated the way `lein test`
  is: evaluating a non-terminating sandboxed call there directly leaves a
  CPU-pegged daemon thread running in that SAME, SHARED process until it is
  restarted. Reproduce a non-terminating scenario via `lein test`, or a
  throwaway `lein run -m clojure.main` process, never the pinned
  `127.0.0.1:7888` one."
  (:require [haselnuss.ext :as ext]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store]))

(def default-timeout-ms
  "How long, in wall-clock milliseconds, one sandboxed call (loading a
  sidecar file's own top-level forms, or one later call to a function it
  defines) is allowed to run before `sci-call!` gives up on it -- generous
  enough for real work, short enough that a build using this sandbox does
  not sit hung on a mistake. Public so both loaders can use it as their own
  `:timeout-ms` default without duplicating the number."
  5000)

(def default-output-limit
  "The cap, in `(count (pr-str result))` characters, on what one sandboxed
  call may successfully return (AC #8) -- generous for any plausible single
  renderer/lower-rule/transform result, small enough to catch a runaway
  string- or tree-building bug rather than let it reach the emitter (or the
  document) it was headed for. Public for the same reason as
  `default-timeout-ms`."
  200000)

(def ^:private ext-namespace
  "The one extra namespace this sandbox adds on top of sci's own default
  core (see this namespace's own docstring, `:namespaces`): every public
  var `haselnuss.ext` defines, bound by reference to the real host
  function -- `raw` (TASK-90), `text`/`map-text`/`text->inlines`
  (TASK-103) -- so there is exactly one implementation of each, tested like
  any other Clojure code, not a second copy living only inside sci config."
  {'raw ext/raw
   'text ext/text
   'map-text ext/map-text
   'text->inlines ext/text->inlines})

(def base-context
  "One locked sci context, built once, the first time anything actually
  needs it (`haselnuss.cli/version`'s own \"pay the cost only if used\"
  idiom). Never evaluates anything directly -- every real use is a fresh
  `fork` of this, one per document build, so nothing a fork's own
  evaluation defines is visible here or in a sibling fork. See this
  namespace's own docstring for the full reasoning behind each option
  below."
  (delay
    (sci/init {:namespaces {'haselnuss.ext ext-namespace}
               :classes {}
               :deny '[eval resolve intern alter-var-root load-string]
               :load-fn (fn [_] nil)})))

(defn fork
  "A fresh `sci/fork` of `base-context` -- what `haselnuss.extensions.
  sidecar`/`rules` each build once per document build (`delay`-wrapped by
  the caller), and what `haselnuss.cli` builds exactly ONCE, sharing it
  between BOTH loaders, when a build passes `--extensions` and
  `--transform` together (this namespace's own docstring, ISOLATION)."
  []
  (sci/fork @base-context))

(def ^:private worker-thread-prefix
  "The fixed prefix every worker thread `bounded-eval!` spawns carries in
  its own name, ahead of that specific call's own `describe` label: an
  abandoned worker (see that function's own docstring on why one can
  outlive its own timeout) would otherwise show up in `top -H`/`jstack` as
  a bare, unhelpful `Thread-N`, with nothing distinguishing it from any
  other thread in the process or naming which sandboxed call it came from
  -- exactly the identifying information someone debugging a CPU-pegged
  process via those tools needs first. Shared by both loaders now, rather
  than named after either one alone -- a `--transform` rule's own runaway
  worker is exactly as identifiable as an `--extensions` renderer's."
  "haselnuss-sandbox-worker: ")

(defn- worker-thread
  "A daemon `Thread`, named `worker-thread-prefix` followed by `describe`
  (`sci-call!`'s own label for this specific call), running `(deliver p
  ...)` from `thunk`'s own result -- `{:ok value}` on a normal return,
  `{:error throwable}` on an exception -- never left running with anything
  blocking the caller once the promise is delivered. `:daemon true` is what
  keeps a thread this function abandons on timeout (see `bounded-eval!`)
  from being able to outlive the JVM process itself."
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
  own docstring has the full reasoning for why this cannot be delegated to
  anything inside sci itself). Returns `{:ok value}`, `{:error throwable}`,
  or `{:timeout true}`.

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
  "The size `sci-call!` measures a successful call's own return value by:
  the length of its `pr-str` representation, a single measure that catches
  both \"one huge string\" and \"one huge nested structure\" without
  walking the value's own shape by hand."
  [value]
  (count (pr-str value)))

(defn- force-realized
  "`value`, with every element of it forced now if it is lazy (`seq?` --
  what a `for`/`map`/`filter` call returns, among others), rather than left
  for a caller to realize later, outside `sci-call!`'s own timeout/capture
  bound. Called from INSIDE `sci-call!`'s own timed, output-capturing
  thunk, and, load-bearingly, nested INSIDE `sci/binding`'s own dynamic
  extent there (`sci-call!`'s own docstring has the full story, including
  the exact regression a round of review once caught this shipping as: an
  earlier version nested the two the other way around, which realizes a
  lazy seq correctly bounded by the timeout but with `sci/out`/`sci/err`
  already unbound, throwing sci's own internal `ClassCastException`
  instead of loading cleanly).

  `doall` on anything that is not `seq?` (a map, a string, a vector, a bare
  keyword) is never attempted -- a safe no-op for the overwhelmingly common
  case, not merely a redundant one.

  Deliberately SHALLOW, not a recursive walk: only `value` itself is
  forced, not a lazy seq nested two or more levels inside an otherwise
  eager structure. That is enough for both of this function's own callers
  -- `extension-entries`/`rule-entries` need exactly `value`'s own top
  level realized to validate it, and a renderer/lower-rule/transform's own
  return value is walked by whatever host code consumes it afterward
  (an emitter's `render-blocks`, `haselnuss.transform`'s own
  `translate-result`), which forces whatever it finds as an ordinary side
  effect of that walk -- so nothing here needs to pre-force a structure
  something else is about to walk anyway."
  [value]
  (if (seq? value) (doall value) value))

(defn sci-call!
  "The one protected-call primitive every sandboxed evaluation goes
  through -- loading a sidecar file's own top-level forms, and every LATER
  call to a renderer/lower rule or transform function it defines. Calls
  `(f)` under this namespace's own runtime bounds, in the order they take
  effect (see this namespace's own docstring for why each one is here and
  what it does and does not cover):

  1. `sci/out`/`sci/err` rebound to fresh, per-call `StringWriter`s --
     established INSIDE the worker thread `bounded-eval!` spawns, since
     `sci/binding` is thread-local and does not propagate to a freshly
     spawned `Thread`. Captured text is discarded.
  2. `force-realized`, called on `(f)`'s result FROM INSIDE that same
     `sci/binding` form's own body -- not outside it, which looks
     equivalent but is not (`force-realized`'s own docstring) -- so a lazy
     return value is fully forced here, under the SAME bound AND the same
     output capture as everything else, rather than by whatever later code
     first walks it.
  3. `bounded-eval!`'s own wall-clock deadline, which the realization step
     above is inside of, not exempt from.
  4. `check-size?` (default true): `output-size`, folded into this
     function so a call that TIMES OUT while still trying to compute an
     oversized value reports as a timeout, not a separate size failure. A
     caller loading a sidecar file's own top-level forms passes `false` --
     the value being checked there is the spec MAP/rule that defines
     future behavior, not a RENDERED result, and necessarily embeds
     opaque, memory-address-bearing function `pr-str`s of no meaningful
     \"size\" at all -- every actual renderer/lower-rule/transform CALL
     still goes through this check via `wrap-fn`.

  `describe` is a short label (a path, a directive/rule name, ...) every
  error this raises names, so a build failure this namespace causes always
  says where it came from, and (`bounded-eval!`/`worker-thread`) what an
  abandoned worker's own thread name says it was doing. `opts` --
  `:timeout-ms`/`:output-limit`, both defaulting to this namespace's own
  `default-timeout-ms`/`default-output-limit`.

  Every `ex-info` this raises is namespaced under THIS namespace
  (`::timeout`/`::evaluation-error`/`::oversized-output`), shared by every
  caller -- a sandboxed call timing out means the same thing regardless of
  whether `--extensions` or `--transform` triggered it, so there is one
  name for it, not one per loader."
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
                       {:type ::timeout :describe describe :timeout-ms timeout-ms}))

       (:error result)
       (throw (ex-info (str describe " raised an error: " (ex-message (:error result)))
                       {:type ::evaluation-error :describe describe}
                       (:error result)))

       :else
       (let [value (:ok result)
             size (and check-size? (output-size value))]
         (when (and check-size? (> size output-limit))
           (throw (ex-info (str describe "'s own output is " size
                                " characters, over this build's " output-limit "-character cap")
                           {:type ::oversized-output :describe describe
                            :size size :limit output-limit})))
         value)))))

(defn wrap-fn
  "`f` -- an already-extracted function value from inside the sandbox (a
  sidecar's own `:emit` renderer, its own `:lower` rule, or a `--transform`
  rule's own `:transform` function) -- adapted into an ordinary host
  function of the same arity that runs every call to it through
  `sci-call!` (the timeout/output-cap/output-capture bound applies to a
  LATER call exactly as much as to loading the file that defined it, since
  the non-terminating or oversized-output code could just as easily live
  inside the function body, only reached once something actually calls it
  for a matching directive or node). `describe` and `opts` are `sci-call!`'s
  own.

  Also re-establishes `sci.ctx-store`'s own context for the duration of the
  call (`ctx-store/with-ctx`): sci's `require` implementation reaches for
  \"the current context\" through that store rather than through anything
  `sci/binding` covers, and a context extracted here and called LATER,
  outside `sci/eval-string*`'s own dynamic extent (exactly this function's
  whole job), has none -- an otherwise-correct `require` call from an
  extracted function throws sci's own \"No context found in:
  sci.ctx-store/*ctx*\" without this, and the intended, graceful \"Could
  not find namespace\" refusal with it.

  `force-realized` is called TWICE for this thunk, deliberately: once
  here, on `(apply f args)`'s own result, still INSIDE `with-ctx`'s own
  dynamic extent; once more inside `sci-call!` itself, on the whole
  thunk's return value, inside ITS OWN `sci/binding` extent. `sci-call!`
  has no way to know that ITS caller's thunk establishes a FURTHER dynamic
  scope of its own -- it can only guarantee realization happens before ITS
  OWN scope closes, not before every scope a more deeply-nested thunk
  might open and close first. The second call, in `sci-call!`, is then a
  safe no-op over an already-realized (cached) seq for this call shape
  specifically, kept rather than removed so `sci-call!`'s own guarantee
  stays unconditionally true for ANY thunk, including a future one that
  opens no scope of its own at all."
  [f ctx describe opts]
  (fn [& args]
    (sci-call! #(ctx-store/with-ctx ctx (force-realized (apply f args))) describe opts)))

(defn collection-of-maps?
  "True when `value` is a plain Clojure collection of maps, distinct from a
  single map itself: a vector, a list, a lazy seq -- what `for`/`map`/
  `filter` naturally return, and the single most idiomatic way an author
  reaches for \"define several of these at once\" -- or a set. Shared by
  both loaders' own \"a file's last form is a map, or a collection of
  them\" contract (`haselnuss.extensions.sidecar`'s extension maps,
  `haselnuss.extensions.rules`' rule maps): each loader's own
  `*-entries` function checks `(map? value)` first and falls back to this
  for anything else, so a literal `vector?` check never silently rejects
  `(for [n [...]] {...})`, forcing an author to remember an unnecessary
  `vec`. `sequential?` alone would miss a set --
  `clojure.lang.PersistentHashSet` does not implement
  `clojure.lang.Sequential` -- so it is checked separately."
  [value]
  (and (or (sequential? value) (set? value)) (every? map? value)))

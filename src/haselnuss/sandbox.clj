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
  a renderer/lower rule or transform function it defines -- runs on a
  daemon worker thread under an external wall-clock deadline
  (`bounded-eval!`; a caller that passes `:stall-allowance-ms` -- only a
  generated shelf's calls do -- is not charged a stall of the whole
  process, such as a stop-the-world garbage collection, up to that
  allowance: the section on the deadline below, TASK-299); a worker is
  reused for a later call only when it
  finished its last one cleanly (TASK-218, the reusable-workers section
  below has exactly what that takes), never after a timeout; on
  timeout, `.interrupt` is still attempted but the caller gets a timeout
  error immediately regardless of whether the worker actually stops.
  On the timeout failure path only, an abandoned worker is also sent
  `Thread.stop` (`stop-abandoned!`), best-effort and unsafe (it may leave
  shared state inconsistent). It ends even a purely interpreted infinite loop
  on JDK < 20; from JDK 20 `Thread.stop` is unsupported, so there `:daemon true` only keeps
  such a worker from outliving the JVM process, not from pegging a CPU core
  indefinitely -- an honestly documented residual (see
  \"What this does not cover\" below). `sci/out`/`sci/err` are rebound to
  fresh writers that keep nothing (`discarding-writer`, TASK-301) for the
  duration of every call, established INSIDE the worker thread's own body
  (dynamic-var bindings are thread-local and do not propagate to another
  `Thread`): what a script prints is dropped as it is written, so a
  printing loop costs time, never memory. (They were `StringWriter`s that
  nothing read, growing with everything a call printed until it ended.)
  The print functions work as before; Java methods called on `*out*`/
  `*err*` themselves, and `(str *out*)` as a way to read back what was
  printed, do not (`sci-call!`, step 1).

  A call's return value is SETTLED on the worker too, under the same
  deadline (TASK-211; `settle`): copied into fresh plain data
  (`plain-copy` -- lazy seqs realised, metadata dropped, anything that is
  not plain data rejected), then, for a rendered result, measured against
  the output cap by printing the copy into a writer that keeps nothing and
  stops past the cap (`check-size!`). Nothing a script made is printed,
  realised or dereferenced on the calling thread afterwards: before this,
  the size check printed the raw result on the CALLING thread, so an
  endless lazy value nested in it, or a `(reify Object (toString ...))` /
  `IDeref` whose printing loops after the first time, hung the whole build
  outside the timeout. A LATER call into a function this namespace already extracted
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

  MEMORY (TASK-301). Bounded: what a call PRINTS (discarded as written,
  above) and what it RETURNS (`plain-copy` counts its values against the
  output cap and `check-size!` measures it without building the printed
  text). NOT bounded: what a script ALLOCATES while it runs -- a vector it
  keeps growing, a huge string, `with-out-str`'s own buffer -- which can
  take the whole process's heap. Why not: the JVM can measure a thread's
  cumulative allocation (`com.sun.management.ThreadMXBean`, which the
  native image supports too) but not the live data it holds, and ordinary
  work allocates garbage fast (measured: an interpreted loop building
  strings, ~0.4 GB a second on HotSpot 17 -- tens of gigabytes over a
  two-minute generated `:document` -- and host code such as `str` or
  `pr-str` far more), so a cap on allocation either fails ordinary work or
  comes long after a script holding its data has filled the heap; and a
  call found over any cap could
  not be stopped where it matters -- `Thread.stop` is unsupported in the
  native image and from JDK 20 -- only abandoned while it goes on
  allocating. What happens instead -- for a MISTAKE, a script that does
  not mean to hold the heap: a script that fills the heap is, allocating
  as it is, usually the thread the `OutOfMemoryError` is thrown on. Its
  call then fails as `::out-of-memory` (its own error, which a generated
  shelf reports as a transient diagnostic when the heap was full), the
  worker retires, and whatever the script held only on its stack is
  garbage again, so the build goes on. A script set on holding the heap is
  not covered: it can catch the error itself (`(catch Object e ...)` --
  sci's default classes include `Object`; so could the script before
  TASK-301), keep its data and carry on or return normally while the
  errors land on other threads. The caller waiting for that call allocates a little too,
  and got the error instead often enough to end the build in every run
  tried (on 128-256 MB heaps, waiting in slices with a stall allowance), so
  it now waits on through one (`wait-for`). The residual risk, honestly:
  the error can land on any other thread allocating while the heap is full
  -- a parallel scan's, a `--serve` request's -- and fail that work instead
  of, or as well as, the script's call; before it does, the process grows to its
  maximum heap (the native binary's default is 80% of RAM; `-Xmx` lowers
  it) and collects again and again, which a generated shelf's stall
  allowance excuses for up to its 30 s (the deadline section below); data a
  script keeps in its own state (a `def`, an atom) outlives the call, as
  long as its fork does, and keeps the heap full; and a call that times out
  before the error is abandoned, and where `Thread.stop` does not work goes
  on allocating until the error ends it on its own thread. Bounding memory
  reliably takes a process with a heap limit of its own: the same process
  boundary as above.

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
            [sci.ctx-store :as ctx-store]
            [sci.impl.vars :as sci-vars])
  (:import (sci.impl.records SciRecord)))

(def default-timeout-ms
  "How long, in wall-clock milliseconds, one sandboxed call (loading a
  sidecar file's own top-level forms, or one later call to a function it
  defines) is allowed to run before `sci-call!` gives up on it -- not
  counting a stall of the whole process when the caller passes a
  `:stall-allowance-ms` (`await-result`, TASK-299) -- generous
  enough for real work, short enough that a build using this sandbox does
  not sit hung on a mistake. Public so both loaders can use it as their own
  `:timeout-ms` default without duplicating the number."
  5000)

(def default-output-limit
  "The cap, in printed characters, on what one sandboxed call may
  successfully return (AC #8) -- generous for any plausible single
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
  "The fixed prefix every worker thread `bounded-eval!` uses carries in
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

;; --- reusable workers (TASK-218) ---------------------------------------------
;;
;; Starting a thread is most of a sandboxed call's cost (~53 of ~70 us,
;; measured for TASK-214), and a fn `:select` is one call per node. So a
;; worker that finished a call CLEANLY goes back to a small idle pool for the
;; next call instead of exiting. "Cleanly" is narrow on purpose -- a worker is
;; reused only when it is observably the thread it was before the call:
;;
;; - the call returned (any throwable, a `ThreadDeath` included, retires it);
;; - it was not abandoned: the caller and the worker race on one
;;   `AtomicInteger` per call (`running` -> `done` by the worker before it
;;   delivers, `running` -> `abandoned` by a timed-out caller before it stops
;;   the worker), so exactly one side wins, and a worker the caller gave up on
;;   never delivers and never returns to the pool, whatever it does next;
;; - its interrupt flag is clear;
;; - BOTH thread-binding frames are the very ones it started the call with --
;;   Clojure's (`Var/getThreadBindingFrame`) and sci's own, which sci keeps in
;;   a separate `ThreadLocal` (`sci.impl.vars/dvals`) -- so no dynamic binding
;;   of either kind, `sci/out`, `ctx-store/*ctx*` or a script's own `^:dynamic`
;;   var, survives into the next call;
;; - its context class loader, priority and daemon flag are unchanged.
;;
;; Anything else and the thread simply ends. Per-call state outside the
;; thread -- the output writers, the TASK-213 issued-fragment set, the sci
;; context -- was already created per call (or per build) on the calling
;; side and only reaches the worker inside the job, so reuse changes nothing
;; there. Idle workers are daemons and end on their own after
;; `worker-keepalive-ms`, so neither a CLI run nor a test run is held up or
;; left with threads behind it; `shutdown-idle-workers!` ends them at once.

(def ^:private worker-keepalive-ms
  "How long an idle worker waits for its next call before ending."
  2000)

(def ^:private max-idle-workers
  "How many idle workers are kept at most; a worker finishing a call when
  that many are already idle ends instead. Sandboxed calls are sequential
  except for nesting (a renderer's ctx callback reaching another untrusted
  renderer), so a handful is plenty."
  4)

(def ^:private ^java.util.concurrent.ConcurrentLinkedDeque idle-workers
  "Workers waiting for a call, most recently used first."
  (java.util.concurrent.ConcurrentLinkedDeque.))

(def ^:private idle-count
  "How many workers `idle-workers` holds, kept alongside it since a
  concurrent deque's own `size` walks it."
  (java.util.concurrent.atomic.AtomicInteger. 0))

(def ^:private late-delivery-ms
  "How long a caller whose call finished exactly at its deadline waits for
  the result the worker is already delivering, before giving up on it."
  1000)

;; --- the deadline, and a stalled process (TASK-299) ----------------------------
;;
;; By default a call's deadline is plain wall-clock time, as it always was:
;; `--extensions` (untrusted ones included), `--transform` and loading a
;; sidecar file get no more than `timeout-ms` from the moment the call starts.
;;
;; A caller may pass `:stall-allowance-ms` (only `haselnuss.library.generated`
;; does, for a shelf script's `:books`, `:document` and `:locate`): its
;; deadline is then wall-clock time the PROCESS runs, up to that allowance. A
;; stop-the-world garbage collection stops every thread -- the worker and the
;; caller waiting for it alike -- and the native image's full collections were
;; seen to last ~9 s under a parallel site scan, longer than the 5 s default:
;; a well-behaved script then timed out at random, and the timeout was cached
;; for the build and saved with its records. So such a caller waits in short
;; slices (`wait-slice-ms`) and checks how long each really took: a slice that
;; returns more than `stall-threshold-ms` later than it asked for means the
;; caller was not running -- the process was stopped, or at least this thread
;; was -- and that overshoot is not charged against the call, until the
;; allowance is used up. Everything else is: a script that loops on a process
;; that keeps running is charged every millisecond and times out exactly as
;; before, at the deadline, with no retry and no second worker. A stall
;; excuses only itself, so a call is charged its deadline within its deadline
;; plus the allowance of wall-clock time the process runs; a stall longer
;; than the allowance left still delays the caller's waking by however long
;; the process was stopped, as it always did.
;;
;; What the allowance exposes, and why it is not for untrusted code: a script
;; can cause the stalls itself. One that keeps a large live heap and allocates
;; in a loop makes the collector stop the process again and again, and each
;; stop is excused: a call can then last its deadline plus the whole allowance
;; (measured on HotSpot with a 420 MB serial-GC heap, a 3 s call took 8.2 s;
;; in the native image, whose full collections take ~9 s, about deadline +
;; 30 s). A shelf script only runs when the library's own directory entry says
;; `scripts: true`, and its timeouts are now transient (`haselnuss.library/
;; transient?`), so this is the trade made there and nowhere else. The
;; allocation itself is bounded by nothing but the heap (the namespace
;; docstring, MEMORY, TASK-301): a script that fills it gets, usually, an
;; `::out-of-memory` failure of its own call, after up to the allowance of
;; excused collections on the way there.
;;
;; Not detected: a worker starved of CPU while the caller keeps waking on time
;; (more runnable threads than cores, with no stall). Measuring the worker's
;; own CPU time would catch that, but needs `ThreadMXBean` CPU times of
;; another thread in the native image, and a script blocked on `@(promise)`
;; uses no CPU at all; such a timeout is instead marked transient by the
;; callers that save results (`haselnuss.library.generated`), so a later run
;; tries again.

(def ^:private wait-slice-ms
  "How long `await-result` waits at a time before it checks the clock."
  100)

(def ^:private stall-threshold-ms
  "How much later than it asked a wait slice may return and still be
  charged in full: ordinary scheduling delay and short collections stay
  well below it, so a running call's deadline is not stretched by them."
  200)

(defn- wait-for
  "What `p` delivers within `ms` milliseconds, `::timeout` when it does
  not, or `::oom` when this thread got an `OutOfMemoryError` while it
  waited (TASK-301). Waiting allocates a little -- the boxed timeout, the
  latch's queue node -- and while a script fills the heap that little can
  be the allocation the collector gives up on: the waiting caller, not the
  script's worker, would then get the error, and it would end the build
  (it did, before this, on a 256 MB heap). The caller has done nothing,
  so it waits on instead; the worker gets its own error soon after. `ms`
  is a primitive, boxed inside the `try`."
  [p ^long ms]
  (try (deref p ms ::timeout)
       (catch OutOfMemoryError _ ::oom)))

(defn- ms-ceil
  "`nanos`, at least 1, as whole milliseconds rounded up."
  ^long [^long nanos]
  (max 1 (quot (+ nanos 999999) 1000000)))

(defn- await-result
  "What the promise `p` delivers, or `::timeout` once the call has been
  charged `timeout-ms` (see the section comment). With a zero
  `allowance-ms`, the default, a plain wall-clock wait. Otherwise every
  slice of waiting counts in full, except a stall's overshoot, until
  `allowance-ms` of it has been excused. Either way an `OutOfMemoryError`
  this thread gets while it waits is not the end of the wait
  (`wait-for`): the time it took is charged as any other."
  [p timeout-ms allowance-ms]
  (let [ms 1000000]
    (if-not (pos? (long allowance-ms))
      (let [end (+ (System/nanoTime) (* ms (long timeout-ms)))]
        (loop []
          (let [left (- end (System/nanoTime))
                result (if (pos? left) (wait-for p (ms-ceil left)) ::timeout)]
            (if (identical? ::oom result) (recur) result))))
      (let [budget (* ms (long timeout-ms))
            slice (* ms (long wait-slice-ms))
            threshold (* ms (long stall-threshold-ms))
            allowance (* ms (long allowance-ms))]
        (loop [charged 0 excused 0]
          (let [left (- budget charged)]
            (if (<= left 0)
              ::timeout
              (let [asked (min left slice)
                    start (System/nanoTime)
                    result (wait-for p (ms-ceil asked))
                    took (- (System/nanoTime) start)]
                (if-not (or (identical? ::timeout result) (identical? ::oom result))
                  result
                  (let [late (- took asked)
                        excuse (long (if (> late threshold) (min late (- allowance excused)) 0))]
                    (recur (+ charged (- took excuse)) (+ excused excuse))))))))))))

(def ^:private ^:const running 0)
(def ^:private ^:const done 1)
(def ^:private ^:const abandoned 2)

(def ^:private retire
  "The job `shutdown-idle-workers!` hands an idle worker to end it."
  (Object.))

;; A worker is a map `{:thread Thread :inbox BlockingQueue}`, a job one of
;; `{:thunk :describe :p :state}` -- maps rather than records, since a record
;; is a class the native image would have to be told to load by name.

(defn- take-idle!
  "True when this call removed `worker` from the pool, nil when it was not
  there. A caller acquiring it and the worker itself ending on its
  keepalive both remove it this way, so exactly one of the two gets it."
  [worker]
  (when (.remove idle-workers worker) (.decrementAndGet ^java.util.concurrent.atomic.AtomicInteger idle-count) true))

(defn- release!
  "Puts `worker` back in the pool; false, so it ends, when the pool is full."
  [worker]
  (let [^java.util.concurrent.atomic.AtomicInteger n idle-count]
    (if (> (.incrementAndGet n) max-idle-workers)
      (do (.decrementAndGet n) false)
      (do (.addFirst idle-workers worker) true))))

(defn- run-job!
  "Runs `job` on `worker`, the current thread's own; true when the worker
  is back in the pool afterwards (see the section comment above for what
  that takes). It goes back BEFORE the result is delivered, so the caller's
  next call finds it idle rather than starting another thread."
  [worker job]
  (let [^Thread self (:thread worker)
        ^java.util.concurrent.atomic.AtomicInteger state (:state job)
        loader (.getContextClassLoader self)
        priority (.getPriority self)
        _ (.setName self (str worker-thread-prefix (:describe job)))
        _ (Thread/interrupted)
        frame (clojure.lang.Var/getThreadBindingFrame)
        sci-frame (sci-vars/get-thread-binding-frame)
        result (try {:ok ((:thunk job))} (catch Throwable t {:error t}))
        ;; Read before the race below: only a caller that already won it
        ;; interrupts, and then this worker loses it.
        clean? (and (contains? result :ok)
                    (not (Thread/interrupted))
                    (identical? frame (clojure.lang.Var/getThreadBindingFrame))
                    (identical? sci-frame (sci-vars/get-thread-binding-frame))
                    (identical? loader (.getContextClassLoader self))
                    (= priority (.getPriority self))
                    (.isDaemon self))]
    (when (.compareAndSet state running done)
      ;; The caller now waits on `p` without a deadline of its own beyond
      ;; `bounded-eval!`'s short grace, so `p` is delivered whatever the
      ;; bookkeeping does; a throw there (an `OutOfMemoryError`, say) retires
      ;; this worker, taking it back out of the pool if it got in.
      (try
        (and clean?
             (do (.setName self (str worker-thread-prefix "idle")) true)
             (release! worker))
        (catch Throwable _
          (take-idle! worker)
          false)
        (finally
          (deliver (:p job) result))))))

(defn- worker-loop
  [worker]
  (let [^java.util.concurrent.BlockingQueue inbox (:inbox worker)]
    (loop []
      (let [job (.poll inbox worker-keepalive-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
        (cond
          (identical? retire job) nil
          job (when (run-job! worker job) (recur))
          ;; Timed out idle: end, unless a caller took this worker from the
          ;; pool just now, in which case its job is on the way.
          (take-idle! worker) nil
          :else (recur))))))

(defn- new-worker
  "A started daemon worker, not in the pool: the caller is about to give it
  a job. `:daemon true` is what keeps one abandoned on timeout (see
  `bounded-eval!`) from being able to outlive the JVM process itself."
  []
  (let [inbox (java.util.concurrent.ArrayBlockingQueue. 1)
        p (promise)
        thread (doto (Thread. ^Runnable (fn [] (worker-loop @p))
                              ^String (str worker-thread-prefix "starting"))
                 (.setDaemon true))
        worker {:thread thread :inbox inbox}]
    (deliver p worker)
    (.start thread)
    worker))

(defn- acquire-worker!
  "An idle worker taken from the pool, or a new one when none is idle --
  never a wait for a busy one, so a nested sandboxed call cannot deadlock."
  []
  (loop []
    (if-let [w (.peekFirst idle-workers)]
      (if (take-idle! w) w (recur))
      (new-worker))))

(defn shutdown-idle-workers!
  "Ends every idle worker now, rather than after its keepalive. Calls in
  flight are unaffected and later calls start fresh workers. For tests and
  for callers that want no sandbox thread left behind a build."
  []
  (loop []
    (when-let [w (.peekFirst idle-workers)]
      (when (take-idle! w) (.offer ^java.util.concurrent.BlockingQueue (:inbox w) retire))
      (recur))))

(defn- stop-abandoned!
  "Best-effort end of an abandoned worker (TASK-147). `.interrupt` alone does
  nothing to an interpreted `(loop [] (recur))`, which would then burn a core
  until the JVM exits, so this also calls the deprecated `Thread.stop`, which
  throws a `ThreadDeath` into the worker. It is unsafe (it can leave shared
  state inconsistent), so it is applied only on the timeout failure path, and
  it is effective only on JDK < 20;
  from JDK 20 `Thread.stop` throws `UnsupportedOperationException`, which is
  swallowed here, and an abandoned worker is then left running as the
  namespace docstring (RUNTIME BOUNDS) says. Looked up reflectively so the
  call neither emits a deprecation warning nor fails to compile on a JDK
  that removed the method."
  [^Thread worker]
  (try
    (.interrupt worker)
    (.invoke (.getMethod Thread "stop" (make-array Class 0)) worker (object-array 0))
    ;; Swallowing is deliberate: this is best-effort cleanup after the caller
    ;; has already been given its timeout result. On JDK 20+ `stop` throws
    ;; UnsupportedOperationException (wrapped in InvocationTargetException by
    ;; reflection), and a JDK without the method would throw
    ;; NoSuchMethodException; in neither case may cleanup mask the timeout.
    (catch Throwable _ nil)))

(defn- bounded-eval!
  "Runs `thunk` on a worker thread named after `describe` -- an idle one
  from the pool, or a new one (`acquire-worker!`, TASK-218) -- bounded by
  `timeout-ms` wall-clock milliseconds -- or, with a positive
  `allowance-ms`, `timeout-ms` of the process running, a stall of the
  whole process not charged until `allowance-ms` of it has been excused
  (`await-result`, TASK-299) -- the external half of this namespace's own layered resource bound (its
  own docstring has the full reasoning for why this cannot be delegated to
  anything inside sci itself). Returns `{:ok value}`, `{:error throwable}`,
  or `{:timeout true}`.

  On timeout, `stop-abandoned!` is called on the abandoned worker (interrupt,
  then the unsafe, best-effort `Thread.stop`, effective only on JDK < 20),
  but this function does not wait to find out whether it had any effect: the
  caller gets `{:timeout true}` immediately, which is what keeps THIS build
  from hanging regardless of whether that worker thread ever actually
  stops. Marking the call abandoned first means that worker never returns
  to the pool, even if it does finish."
  [thunk describe timeout-ms allowance-ms]
  (let [p (promise)
        state (java.util.concurrent.atomic.AtomicInteger. running)
        worker (acquire-worker!)]
    (.offer ^java.util.concurrent.BlockingQueue (:inbox worker) {:thunk thunk :describe describe :p p :state state})
    (let [result (await-result p timeout-ms allowance-ms)]
      (cond
        (not= ::timeout result) result
        ;; Won the race: the worker can no longer deliver or be reused.
        (.compareAndSet state running abandoned) (do (stop-abandoned! (:thread worker))
                                                     {:timeout true})
        ;; Lost it: the worker finished at the deadline and is delivering
        ;; (`run-job!` does so in a `finally`). Still bounded, so no path
        ;; here waits without limit: a plain wall-clock wait, which waits
        ;; on through an OutOfMemoryError like any other (`wait-for`).
        :else (let [late (await-result p late-delivery-ms 0)]
                (if (identical? ::timeout late)
                  {:error (IllegalStateException. "the sandbox worker finished but never handed back its result")}
                  late))))))

;; --- settling a result on the worker (TASK-211) ------------------------------
;;
;; Generalised from `haselnuss.library.generated` (TASK-158), which solved
;; this for shelf scripts alone.

(def ^:private scalar-classes
  "The classes of the plain scalars any sandboxed call may answer. All
  final, so no script value can be one of them with behaviour of its own."
  #{String Boolean Character Long Integer Short Byte Double Float BigInteger BigDecimal
    clojure.lang.BigInt clojure.lang.Ratio clojure.lang.Keyword})

(def ^:private failure-token
  "Marks the two failures `settle` raises itself, so `sci-call!` can tell
  them from an `ex-info` a script threw with the same keys."
  (Object.))

(defn- result-failure
  [failure message data]
  (ex-info message (assoc data ::failure failure ::token failure-token)))

(defn- over-cap [limit]
  (result-failure ::oversized-output
                  (str "its result is over the " limit "-character output cap")
                  {:limit limit}))

(defn- bounded-pr [x] (binding [*print-length* 8 *print-level* 4] (pr-str x)))

(def ^:private kind-allows
  "What each result kind (`plain-copy`) accepts beyond plain data."
  {:data {:also ""}
   :result {:records? true :also ", or a fragment its ctx's callbacks rendered"}
   :spec {:fns? true :also ", plus functions, vars and regexes"}})

(defn- host-record?
  "True for a record a host namespace defined -- an emitter's `Rendered`,
  which a renderer gets from its ctx's `:render-blocks` callback and hands
  back -- as opposed to one a script defined with `defrecord`, which sci
  makes a `SciRecord` that may carry its own `toString`."
  [x]
  (and (instance? clojure.lang.IRecord x) (not (instance? SciRecord x))))

(defn plain-copy
  "`value` rebuilt from fresh maps, vectors, lists and sets of plain
  scalars and symbols, with no metadata (so a script cannot forge source
  positions), or a throw naming the first thing that is not plain data --
  a `reify`, an atom, a script `defrecord`/`deftype` -- since printing,
  realising or dereferencing one later runs script code outside the
  sandbox's timeout. Lazy seqs become lists, realised here. A sorted map or
  set becomes a plain one: its comparator could be script code.

  `kind` widens that for what a call is expected to answer:

  - `:data` (default) -- nothing more. A shelf script's answers.
  - `:result` -- also a record a host namespace defined (`host-record?`),
    kept as that record with every value copied and its metadata dropped;
    any extra key must be a keyword. One with no metadata whose every
    value copies to itself (a `Rendered`'s one String field always does)
    is returned as the SAME instance, not a rebuilt one: the emitters
    accept an untrusted renderer's `Rendered` only by identity with one
    their ctx callbacks issued for that call (TASK-213), so a copy must
    not break that identity -- and a rebuilt or modified one is exactly
    what they refuse. What `wrap-fn` calls answer: a renderer hands back
    the emitter's own `Rendered` its ctx callbacks built.
  - `:spec` -- also functions (`fn?`: a sci fn is an ordinary host
    function whose printing runs no script code; its metadata, script data
    that could hold a `reify`, is dropped), sci vars and regexes, kept as
    they are. A sidecar file's or shelf script's last form, whose
    functions are the API.

  Counts every value against `limit`: each prints as at least one
  character, so more than `limit` of them is over the cap, which also
  bounds an endless lazy seq. Honours the interrupt a timed-out worker is
  sent. Meant to run on the worker, under the timeout -- `sci-call!` calls
  it there for every result; public for `haselnuss.library.generated`'s
  load-time check of a shelf script's `:data`."
  ([value limit] (plain-copy value limit :data))
  ([value limit kind]
   (let [{:keys [fns? records? also]} (kind-allows kind)
         budget (volatile! limit)
         not-plain (fn [x path]
                     (throw (result-failure
                             ::unsupported-result
                             (str "its result holds a " (.getName (class x)) " at " (bounded-pr path)
                                  "; a sandboxed call answers plain data only (maps, vectors, lists,"
                                  " sets, strings, numbers, keywords, symbols, booleans, characters,"
                                  " nil)" also)
                             {:path path})))
         copy (fn copy [x path]
                (when (neg? (vswap! budget dec)) (throw (over-cap limit)))
                (when (Thread/interrupted) (throw (InterruptedException.)))
                (cond
                  (nil? x) nil
                  (contains? scalar-classes (class x)) x
                  (instance? clojure.lang.Symbol x) (symbol (namespace x) (name x))
                  ;; A function's metadata is script data like any other
                  ;; and could hold a `reify`; nothing needs it (TASK-214).
                  (and fns? (fn? x)) (if (meta x) (with-meta x nil) x)
                  (and fns? (or (instance? sci.lang.Var x)
                                (instance? java.util.regex.Pattern x))) x
                  (and records? (host-record? x))
                  (let [entries (mapv (fn [[k v]]
                                        (if (keyword? k) [k v (copy v (conj path k))] (not-plain k path)))
                                      (seq x))]
                    ;; Unchanged -> the very instance, so an emitter can
                    ;; still recognise a `Rendered` its own callback
                    ;; issued by identity (TASK-213).
                    (if (and (nil? (meta x)) (every? (fn [[_ v c]] (identical? v c)) entries))
                      x
                      (reduce (fn [r [k _ c]] (assoc r k c)) (with-meta x nil) entries)))
                  ;; The path takes the COPIED key: a failure's `:path`
                  ;; leaves the worker in `ex-data`, and the script's
                  ;; own key could be anything (TASK-214).
                  (map? x) (persistent! (reduce (fn [m [k v]]
                                                  (let [k' (copy k path)]
                                                    (assoc! m k' (copy v (conj path k')))))
                                                (transient {})
                                                (seq x)))
                  (vector? x) (into [] (map-indexed (fn [i v] (copy v (conj path i)))) (seq x))
                  (set? x) (into #{} (map #(copy % path)) (seq x))
                  (seq? x) (apply list (doall (map-indexed (fn [i v] (copy v (conj path i))) x)))
                  :else (not-plain x path)))]
     (copy value []))))

(def ^:private char-array-class (class (char-array 0)))

(defn- char-array? [x] (instance? char-array-class x))

(defn- check-size!
  "Throws when plain `value` prints to more than `limit` characters,
  printing into a writer that keeps nothing and stops at the cap, so
  measuring costs no memory and ends early. Only ever given a `plain-copy`,
  so the printing runs no script code."
  [value limit]
  (let [n (volatile! 0)
        add! (fn [k] (when (> (vswap! n + (long k)) limit) (throw (over-cap limit))))
        ;; A proxy method takes every overload of its name: write(int),
        ;; write(String) and write(char[]) arrive as the 1-arity, the two
        ;; (buffer, offset, length) overloads as the 3-arity.
        w (proxy [java.io.Writer] []
            (write
              ([x] (add! (cond (string? x) (count x) (char-array? x) (alength ^chars x) :else 1)))
              ([_ _ len] (add! len)))
            (flush [])
            (close []))]
    (binding [*print-length* nil *print-level* nil] (print-method value w))
    value))

(defn- settle
  "`value`, a sandboxed call's result, made safe to hand back to the
  calling thread: `plain-copy`'d as `kind`, then -- except for a `:spec`,
  whose functions print as opaque, memory-address-bearing objects of no
  meaningful size -- measured against `limit` (`check-size!`). Runs on
  the worker, under the timeout."
  [value limit kind]
  (let [copied (plain-copy value limit kind)]
    (if (= :spec kind) copied (check-size! copied limit))))

(def ^:private max-cause-depth
  "How many links of a failed call's cause chain `sanitize-error` keeps;
  a script can call `.initCause` on an exception it holds, so the chain
  is not guaranteed to end."
  16)

(defn- scalar-entries
  "The entries of ex-data map `data` whose key is a keyword and whose value
  is nil or a plain scalar (`scalar-classes`) -- what sci's own located
  errors carry (`:type`, `:line`, `:column`, `:file`) and nothing a script
  could make run code when printed."
  [data]
  (into {}
        (filter (fn [[k v]] (and (keyword? k) (or (nil? v) (contains? scalar-classes (class v))))))
        data))

(def ^:private max-message-length
  "How many characters of a failed call's message `sanitize-error` keeps."
  4000)

(defn- bounded-message
  [^Throwable t]
  (when-let [m (.getMessage t)]
    (if (> (count m) max-message-length) (str (subs m 0 max-message-length) "...") m)))

(defn- sanitize-error
  "`t`, the throwable a sandboxed call raised, rebuilt so nothing in it is
  script data (TASK-214): EVERY link of its cause chain becomes a fresh
  `ExceptionInfo` with the link's message (bounded, `max-message-length`)
  and stack trace, whose `ex-data` keeps only `scalar-entries` -- plus, for
  a link that was not an `ExceptionInfo`, `::error-class` naming its class,
  and its class name as the message when it had none (a
  `StackOverflowError` still reads as one). Nothing else is carried over:
  not the script's ex-data objects, not its suppressed exceptions (a script
  can `.addSuppressed` anything onto an exception it holds, and
  `.printStackTrace` prints them). So `haselnuss.cli/fail!` printing it, or
  anything printing the chain, runs no script code. `settle`'s own
  failures pass through: `sci-call!` reads their token. Runs on the
  worker, under the timeout, since reading a script's ex-data can run
  script code."
  [^Throwable t depth]
  (if (identical? failure-token (::token (ex-data t)))
    t
    (let [cause (.getCause t)
          cause' (when (and cause (pos? depth)) (sanitize-error cause (dec depth)))
          class-name (.getName (class t))
          ^Throwable copy (if (instance? clojure.lang.IExceptionInfo t)
                            (ex-info (or (bounded-message t) "")
                                     ;; Only this function names a class: a script's
                                     ;; own ::error-class is not carried over.
                                     (dissoc (scalar-entries (ex-data t)) ::error-class)
                                     cause')
                            (ex-info (or (bounded-message t) class-name)
                                     {::error-class class-name} cause'))]
      (.setStackTrace copy (.getStackTrace t))
      copy)))

(defn- out-of-memory
  "The first `OutOfMemoryError` among the first `max-cause-depth` links of
  `t`'s cause chain, or nil. Read from the throwable itself, never from
  ex-data, which a script can fill with whatever keys it likes."
  [^Throwable t]
  (loop [^Throwable c t n max-cause-depth]
    (cond (nil? c) nil
          (instance? OutOfMemoryError c) c
          (zero? n) nil
          :else (recur (.getCause c) (dec n)))))

(defn- oom-detail
  "`oom`, an `OutOfMemoryError`, as `class: message`."
  [^Throwable oom]
  (str (.getName (class oom)) (some->> (bounded-message oom) (str ": "))))

(def ^:private heap-exhausted-messages
  "How an `OutOfMemoryError` for a full heap begins: HotSpot's two, then
  the native image's. Others say the request alone was impossible --
  HotSpot's \"Requested array size exceeds VM limit\", the native image's
  \"Array allocation too large.\" (any array larger than its heap, probed
  on GraalVM 21) -- and would fail the same way on any run."
  ["Java heap space" "GC overhead limit exceeded" "Garbage-collected heap size exceeded"])

(defn- heap-exhausted?
  "True when `oom`'s message says the heap was full (`heap-exhausted-
  messages`), which another thread's allocation can cause, as against a
  request no heap could meet. HotSpot says \"Java heap space\" for an
  array larger than the whole heap too (`(object-array 2147483000)` on a
  small one), so that case reads as a full heap there."
  [^Throwable oom]
  (let [m (str (.getMessage oom))]
    (boolean (some #(.startsWith m ^String %) heap-exhausted-messages))))

(defn- error-reason
  "What to print after \"raised an error:\" for `error`, a
  `sanitize-error` copy (TASK-318): its own message, else the first
  non-blank message down its cause chain (sci wraps a host error in an
  `ExceptionInfo` whose message is \"\", and `sanitize-error` keeps that),
  else the first `::error-class` along the chain -- so a
  `StackOverflowError` reads as one -- else the copy's own class name."
  [^Throwable error]
  (let [chain (take (inc max-cause-depth) (take-while some? (iterate ex-cause error)))
        reason (or (some #(let [m (ex-message %)] (when (and m (seq (.trim ^String m))) m)) chain)
                   (some #(let [c (::error-class (ex-data %))] (when (string? c) c)) chain)
                   (.getName (class error)))]
    ;; Defence in depth: never longer than `max-message-length`.
    (if (> (count reason) max-message-length)
      (str (subs reason 0 max-message-length) "...")
      reason)))

(defn- sanitized-call
  "`(g)`, with any throwable it raises replaced by `sanitize-error`'s copy
  -- or, should reading that throwable itself fail, by a plain one saying
  so, so the failure of the sanitising never lets the original through.
  One with an `OutOfMemoryError` in its cause chain (sci wraps a host
  error in its own located one) is replaced by an `::out-of-memory`
  failure instead (TASK-301), marked with `failure-token` so a script
  cannot forge one, and carrying `:heap-exhausted` (`heap-exhausted?`)."
  [g]
  (try
    (g)
    (catch Throwable t
      (throw (try
               (if-let [oom (out-of-memory t)]
                 (result-failure ::out-of-memory (oom-detail oom) {:heap-exhausted (heap-exhausted? oom)})
                 (sanitize-error t max-cause-depth))
               (catch Throwable _
                 (RuntimeException. (str (.getName (class t)) " (its details could not be read)"))))))))

(defn- discarding-writer
  "What a sandboxed call's `*out*` and `*err*` are bound to (TASK-301): a
  fresh `Writer` that keeps nothing it is given, so printing costs no
  memory however much a script prints. Nothing has ever read a script's
  output -- it went into `StringWriter`s that were dropped after the call,
  growing without bound while it ran. Fresh per call. Its class is not one
  sci lets a script call methods on, so `(.write *out* \"x\")` is refused
  where it worked on a `StringWriter` (a subclass of that would be
  refused too: sci allows the exact class); the print functions, which
  write through Clojure itself, are unaffected (`sci-call!`, step 1)."
  ^java.io.Writer []
  (java.io.Writer/nullWriter))

(defn sci-call!
  "The one protected-call primitive every sandboxed evaluation goes
  through -- loading a sidecar file's own top-level forms, and every LATER
  call to a renderer/lower rule or transform function it defines. Calls
  `(f)` under this namespace's own runtime bounds, in the order they take
  effect (see this namespace's own docstring for why each one is here and
  what it does and does not cover):

  1. `sci/out`/`sci/err` rebound to fresh, per-call writers that keep
     nothing (`discarding-writer`, TASK-301) -- established INSIDE the
     worker thread `bounded-eval!` runs it on, since `sci/binding` is
     thread-local and does not propagate to another `Thread`. What a
     script prints is dropped as it is written. `print`, `println`,
     `printf`, `prn`, `pr`, `flush` and `newline` work as ever; a Java
     method called on `*out*`/`*err*` directly (`.write`, `.append`,
     `.flush`, `.close`) is refused by sci, whose default classes allowed
     them on the `StringWriter` this replaced, and `(str *out*)` no longer
     returns what was printed.
  2. `(f)`, and then `settle` on its result -- `plain-copy` (as `kind`)
     and, unless `kind` is `:spec`, `check-size!` -- both FROM INSIDE that
     same `sci/binding` form's own body, and inside `opts`' `:scope` when
     given, so realising a lazy value nested anywhere in the result runs
     with the same output capture and dynamic scope as `(f)` itself.
     (Realising one OUTSIDE `sci/binding` throws sci's own internal
     `ClassCastException`; outside `wrap-fn`'s `ctx-store/with-ctx`, a
     `require` in it fails with \"No context found\" -- both regressions a
     review once caught.)
  3. `bounded-eval!`'s own deadline -- wall-clock, unless `opts` give a
     `:stall-allowance-ms` (see below) -- which everything above,
     the copy and the measuring included, is inside of, not exempt from: a
     call that TIMES OUT while still trying to compute an oversized value
     reports as a timeout, and nothing a script made is realised, printed
     or dereferenced on the calling thread afterwards (TASK-211).

  `kind` (`plain-copy`): `:data` (default) for plain data, `:result` for
  a `wrap-fn` call (plain data or a host-made record such as an emitter's
  `Rendered`), `:spec` for a sidecar file's own last form -- plain data
  plus the functions that define future behaviour; it is copied but not
  measured, since a function prints as an opaque, memory-address-bearing
  object of no meaningful \"size\" at all, and every actual
  renderer/lower-rule/transform CALL is measured via `wrap-fn`.

  `describe` is a short label (a path, a directive/rule name, ...) every
  error this raises names, so a build failure this namespace causes always
  says where it came from, and (`bounded-eval!`/`run-job!`) what an
  abandoned worker's own thread name says it was doing. `opts` --
  `:timeout-ms`/`:output-limit`, both defaulting to this namespace's own
  `default-timeout-ms`/`default-output-limit`; `:scope`, a function of a
  thunk that runs it inside the caller's own dynamic scope (`wrap-fn`'s
  `ctx-store/with-ctx`), applied around both `(f)` and `settle`;
  `:stall-allowance-ms`, default 0, how much of a stall of the whole
  process the call is not charged (the deadline section above,
  TASK-299). Leave it at 0 for anything an untrusted file defines: a
  script can cause such stalls itself, by its own allocation, and so
  stretch its own call by up to the allowance. Only
  `haselnuss.library.generated` passes one. With it, the `::timeout`
  message's \"did not finish within Nms\" counts the N milliseconds the
  call was charged, which can be later than N wall-clock milliseconds.

  Every `ex-info` this raises is namespaced under THIS namespace
  (`::timeout`/`::evaluation-error`/`::oversized-output`/
  `::unsupported-result`/`::out-of-memory`), shared by every caller --
  `::out-of-memory` (TASK-301) when an `OutOfMemoryError` ended the call
  on its worker, which may mean the script filled the heap or that
  something else in the process did (the namespace docstring, MEMORY);
  read from the error itself, never from a script's ex-data. Its
  `:heap-exhausted` is true when the error says the heap was full, false
  when it says the request alone was impossible (`heap-exhausted?`): only
  the first can depend on the run. A sandboxed call
  timing out means the same thing regardless of whether `--extensions` or
  `--transform` triggered it, so there is one name for it, not one per
  loader."
  ([f describe opts] (sci-call! f describe opts :data))
  ([f describe {:keys [timeout-ms output-limit scope stall-allowance-ms]
                :or {timeout-ms default-timeout-ms output-limit default-output-limit
                     stall-allowance-ms 0}}
    kind]
   {:pre [(contains? kind-allows kind)]}
   (let [out (discarding-writer)
         err (discarding-writer)
         scope (or scope (fn [g] (g)))
         result (bounded-eval! (fn []
                                 (sanitized-call
                                  #(sci/binding [sci/out out sci/err err]
                                     (scope (fn [] (settle (f) output-limit kind))))))
                               describe timeout-ms stall-allowance-ms)
         error (:error result)
         failure (cond (identical? failure-token (::token (ex-data error))) (::failure (ex-data error))
                       ;; Raised by the worker's own bookkeeping, past
                       ;; `sanitized-call`: the JVM's, so no script data.
                       (instance? OutOfMemoryError error) ::out-of-memory)]
     (cond
       (:timeout result)
       (throw (ex-info (str describe " did not finish within " timeout-ms "ms")
                       {:type ::timeout :describe describe :timeout-ms timeout-ms}))

       (= ::out-of-memory failure)
       (let [raw? (instance? OutOfMemoryError error)]
         (throw (ex-info (str describe " ran out of memory ("
                              (if raw? (oom-detail error) (ex-message error))
                              ")")
                         {:type ::out-of-memory :describe describe
                          :heap-exhausted (if raw?
                                            (heap-exhausted? error)
                                            (true? (:heap-exhausted (ex-data error))))})))

       (= ::oversized-output failure)
       (throw (ex-info (str describe "'s own output is over this build's " output-limit
                            "-character cap")
                       {:type ::oversized-output :describe describe :limit output-limit}))

       (= ::unsupported-result failure)
       (throw (ex-info (str describe "'s own output is not plain data: " (ex-message error))
                       {:type ::unsupported-result :describe describe
                        :path (:path (ex-data error))}))

       error
       ;; A StackOverflowError (deep nesting) has no message; name its
       ;; class rather than end the sentence on nothing (TASK-158 review).
       ;; `error` is `sanitize-error`'s copy, never the script's own
       ;; exception, so its ex-data is safe to print (TASK-214).
       (throw (ex-info (str describe " raised an error: " (error-reason error))
                       {:type ::evaluation-error :describe describe}
                       error))

       :else (:ok result)))))

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

  That context is passed as `sci-call!`'s `:scope`, not opened inside the
  thunk, so it also covers `sci-call!`'s own settling of the result: a lazy
  value the call returns is realised by that copy, and a `require` in it
  needs the context as much as one in the function body does.

  The result is settled as `sci-call!`'s `:result` kind: plain data, or a
  record the host made -- a renderer hands back the emitter's `Rendered`
  its ctx's `:render-blocks`/`:render-inlines` callbacks gave it."
  [f ctx describe opts]
  (let [opts (assoc opts :scope (fn [g] (ctx-store/with-ctx ctx (g))))]
    (fn [& args]
      (sci-call! #(apply f args) describe opts :result))))

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

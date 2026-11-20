(ns haselnuss.test-parallel
  "Runs the tests of an opted-in namespace concurrently (TASK-147 AC #3).

  Most of the suite's wall time is waiting on real child processes
  (`pdflatex`, `lein`) that each test starts in its own fresh temp
  directory, so the tests of such a namespace are independent and the
  machine's other cores sit idle while they run one after another. A
  namespace opts in with `^:parallel` metadata on its `ns` form; a single
  test inside it opts back out with `^:serial` on its `deftest` (for a
  test that does something process-global, such as `with-redefs` on a
  `clojure.core` var). Serial tests run first, alone, so they never
  overlap a parallel one.

  What this does NOT change: every test still runs the same real toolchain
  and makes the same assertions. Only the scheduling differs. Namespaces
  themselves still run one after another, and a namespace that declares
  `use-fixtures` is never parallelised (the fixtures would wrap each test
  separately and the `:once` guarantee would be lost).

  Installed by `:injections` in project.clj's `:test` profile, so it only
  affects `lein test`; an nREPL `run-tests` is untouched.

  TASK-302 adds what `bin/test` (several `lein test` JVMs at once) needs
  from inside each JVM, all driven by environment variables a plain `lein
  test` does not set: per-namespace and per-test timings
  (`HASELNUSS_TEST_TIMINGS`), a smaller thread budget per JVM
  (`HASELNUSS_TEST_THREADS`), a barrier (`HASELNUSS_TEST_BARRIER`)
  that holds a JVM's tests until the file it names exists, and checks of
  bin/test's plan against what Clojure reads (`HASELNUSS_TEST_PLAN`; see
  `check-discovery!` and `timing-check`). The test metadata `bin/test` and
  the gates read:

  - `^:slow` (deftest or `ns`): left out of the fast gate (`lein test
    :fast`). For a test that took about 12 s or more on its own, and for
    namespaces that mostly drive a packaged artifact, nested Leiningen,
    headless Chrome or `pdflatex`.
  - `^:timing` (deftest or `ns`): asserts a wall-clock bound, or an
    outcome of thread scheduling, that a loaded machine can miss.
    `bin/test` runs these after its shards, in a quiet phase; plain `lein
    test` runs them in place as before. Such a namespace is then loaded in
    two JVMs, so an `is` in its namespace-level setup (a shared `delay`)
    is made, and counted, twice: keep assertions out of shared setup in a
    namespace that has `^:timing` tests.
  - `^:shares-target` (`ns` only): builds into or reads Leiningen's
    `target/` tree, which `lein uberjar` auto-cleans, so `bin/test` keeps
    all such namespaces in one shard."
  (:require [clojure.java.io :as io]
            [clojure.test :as t])
  (:import (java.util.concurrent Semaphore)))

(def ^:private max-concurrent
  "Bounded rather than one thread per test: each running test is a
  `pdflatex` (or a JVM), and 12 at once on a small machine is slower than
  8, not faster. `bin/test` lowers it (`HASELNUSS_TEST_THREADS`) so that
  its JVMs together do not oversubscribe the machine."
  (or (some-> (System/getenv "HASELNUSS_TEST_THREADS") parse-long (max 1))
      (max 2 (min 8 (.availableProcessors (Runtime/getRuntime))))))

(def ^:private barrier
  "Set by `bin/test` for its quiet `^:timing` phase: that JVM loads its
  namespaces alongside the shards, then waits here until all but at most
  one shard are done and `bin/test` creates this file."
  (delay
    (when-let [path (System/getenv "HASELNUSS_TEST_BARRIER")]
      (let [file (io/file path)
            ;; bin/test's own PID: if it is gone (killed, interrupted), the
            ;; file never comes, and this JVM must not wait forever.
            owner (some-> (System/getenv "HASELNUSS_TEST_BARRIER_OWNER") parse-long)
            owner-alive? #(or (nil? owner)
                              (.orElse (.map (java.lang.ProcessHandle/of owner)
                                             (reify java.util.function.Function
                                               (apply [_ h] (.isAlive ^java.lang.ProcessHandle h))))
                                       false))]
        (while (not (.exists file))
          (when-not (owner-alive?)
            (binding [*out* *err*]
              (println "haselnuss.test-parallel: bin/test is gone; not running the timing phase"))
            (System/exit 3))
          (Thread/sleep 200))))))

(defn- parallel-ns?
  [ns-obj]
  (and (:parallel (meta ns-obj))
       (empty? (::t/once-fixtures (meta ns-obj)))
       (empty? (::t/each-fixtures (meta ns-obj)))))

(defn- run-concurrently!
  [vars]
  (let [permits (Semaphore. max-concurrent)
        tasks (mapv (fn [v]
                      (future
                        (.acquire permits)
                        (try
                          (t/test-var v)
                          (finally (.release permits)))))
                    vars)]
    (run! deref tasks)))

(def ^:private timings-file
  "Where to append per-namespace and per-test wall times (TASK-302), one
  EDN map per line, or nil to record nothing. Set by `bin/test`, which
  reads them back to balance its shards and to report the slowest
  namespaces; a plain `lein test` with the variable unset is unaffected."
  (System/getenv "HASELNUSS_TEST_TIMINGS"))

(defn- record-timing!
  [entry]
  (when timings-file
    (locking timings-file
      (spit timings-file (str (pr-str entry) "\n") :append true))))

(defn- elapsed-secs
  [start-nanos]
  (/ (Math/round (/ (- (System/nanoTime) start-nanos) 1e7)) 100.0))

(defn- timing-report
  "Wraps `report` so the begin/end events of each test var record that
  var's wall time. Bound per namespace rather than installed globally so
  it composes with Leiningen's own `report` hook, which it calls."
  [report]
  (let [starts (atom {})
        asserts (atom {})]
    (fn [m]
      (case (:type m)
        :begin-test-var (swap! starts assoc (:var m) (System/nanoTime))
        :end-test-var (when-let [start (get @starts (:var m))]
                        (let [{var-ns :ns var-name :name} (meta (:var m))]
                          (record-timing! {:var (symbol (str (ns-name var-ns)) (str var-name))
                                           :secs (elapsed-secs start)
                                           :assert (get @asserts (:var m) 0)})))
        (:pass :fail :error) (when-let [v (first t/*testing-vars*)]
                               (swap! asserts update v (fnil inc 0)))
        nil)
      (report m))))

;; --- bin/test's plan, checked from inside the JVM ------------------------------
;;
;; bin/test reads namespaces and tags off the source text with awk. That is a
;; line-based guess, and a wrong guess would drop tests while every JVM
;; reports green -- `(ns` alone on its line, `^{:timing true}` metadata. So
;; each JVM it starts checks the guess against what Clojure itself reads, and
;; fails loudly on a difference. `HASELNUSS_TEST_PLAN` names bin/test's plan:
;; one `name<TAB>shares-target<TAB>has-timing-tests` line per namespace.

(def ^:private plan
  (delay
    (when-let [path (System/getenv "HASELNUSS_TEST_PLAN")]
      (into {}
            (for [line (line-seq (io/reader path))
                  :let [[ns-str shared timing] (.split ^String line "\t")]
                  :when (seq ns-str)]
              [(symbol ns-str) {:shared? (= "1" shared) :timing? (= "1" timing)}])))))

(defn- ns-form
  "The `ns` form of a source file, read the way Leiningen's own test
  discovery (bultitude) does: forms in order until one is `(ns ...)`."
  [^java.io.File file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop []
        (let [form (try (read {:eof ::eof :read-cond :allow} r)
                        (catch Exception e
                          (throw (ex-info (str "bin/test plan check: cannot read " file ": " (.getMessage e))
                                          {:file (str file)} e))))]
          (cond (= ::eof form) nil
                (and (seq? form) (= 'ns (first form))) form
                :else (recur)))))))

(defn- check-discovery!
  "Throws unless bin/test's plan names exactly the namespaces the files
  under test/ declare, with the same `^:shares-target`."
  []
  (when @plan
    (let [read-back (into {}
                          (for [^java.io.File f (file-seq (io/file "test"))
                                :when (and (.isFile f) (re-find #"\.cljc?$" (.getName f)))
                                :let [form (ns-form f)]
                                :when form
                                :let [ns-sym (second form)]]
                            [ns-sym (boolean (:shares-target (meta ns-sym)))]))
          planned (into {} (map (fn [[k v]] [k (:shared? v)])) @plan)]
      (when (not= read-back planned)
        (throw (ex-info (str "bin/test plan check: bin/test's namespaces differ from the ns forms under test/ "
                             "(name, shares-target) -- missing from the plan: "
                             (pr-str (sort-by str (remove (set planned) read-back)))
                             "; planned but not read: "
                             (pr-str (sort-by str (remove (set read-back) planned))))
                        {:read read-back :planned planned}))))))

(def ^:private timing-check
  "In a shard JVM (`HASELNUSS_TEST_PHASE=shard`), whose `:phase shard`
  selector leaves out every `^:timing` test: throws if a namespace loaded
  here has one that bin/test did not also send to its timing phase -- a
  test nothing would run."
  (delay
    (when (and @plan (= "shard" (System/getenv "HASELNUSS_TEST_PHASE")))
      (let [missed (for [[ns-sym {:keys [timing?]}] @plan
                         :when (not timing?)
                         :let [ns-obj (find-ns ns-sym)]
                         :when ns-obj
                         v (vals (ns-interns ns-obj))
                         :let [m (meta v)]
                         :when (and (or (:test m) (:leiningen/skipped-test m))
                                    (or (:timing m) (:timing (meta ns-obj))))]
                     (symbol (str ns-sym) (str (:name m))))]
        (when (seq missed)
          (throw (ex-info (str "bin/test plan check: ^:timing tests bin/test did not see, "
                               "so neither phase would run them: " (pr-str (sort-by str missed)))
                          {:missed (vec missed)})))))))

(defn- timed
  "Wraps a `test-ns` implementation so it records the namespace's wall
  time (and, through `timing-report`, each test's) when `timings-file`
  is set. Waits at the barrier and runs the plan's timing check first."
  [test-ns-fn]
  (fn [ns-designator]
    @barrier
    @timing-check
    (if-not timings-file
      (test-ns-fn ns-designator)
      (let [start (System/nanoTime)
            result (binding [t/report (timing-report t/report)]
                     (test-ns-fn ns-designator))]
        (record-timing! {:ns (ns-name (the-ns ns-designator))
                         :secs (elapsed-secs start)
                         :test (:test result 0)
                         :assert (+ (:pass result 0) (:fail result 0) (:error result 0))})
        result))))

(defn install!
  "Wraps `clojure.test/test-ns` so a `^:parallel` namespace runs its
  tests concurrently, and so `HASELNUSS_TEST_TIMINGS` (see
  `timings-file`) records how long each namespace and test took.
  Idempotent.

  `test-ns` rather than `test-vars` is the seam: clojure.test is compiled
  with direct linking, so `test-ns`'s own call to `test-vars` cannot be
  intercepted, while the caller (Leiningen's generated form, `run-tests`
  users going through the var) does see a replaced `test-ns`. This
  mirrors clojure.test's own `test-ns` body: begin/end reports, a fresh
  report-counter ref, and the counters as the result."
  []
  (when (System/getenv "HASELNUSS_TEST_CHECK_DISCOVERY")
    (check-discovery!))
  (when-not (::installed (meta #'t/test-ns))
    (let [original t/test-ns]
      (alter-var-root
       #'t/test-ns
       (constantly
        (timed
         (fn [ns-designator]
           (let [ns-obj (the-ns ns-designator)]
             (if-not (parallel-ns? ns-obj)
               (original ns-designator)
               (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                 (t/do-report {:type :begin-test-ns :ns ns-obj})
                 (let [tests (filter (comp :test meta) (vals (ns-interns ns-obj)))
                       {serial true parallel false} (group-by (comp boolean :serial meta) tests)]
                   (when (seq serial) (t/test-vars serial))
                   (run-concurrently! parallel))
                 (t/do-report {:type :end-test-ns :ns ns-obj})
                 @t/*report-counters*)))))))
      (alter-meta! #'t/test-ns assoc ::installed true))))

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
  affects `lein test`; an nREPL `run-tests` is untouched."
  (:require [clojure.test :as t])
  (:import (java.util.concurrent Semaphore)))

(def ^:private max-concurrent
  "Bounded rather than one thread per test: each running test is a
  `pdflatex` (or a JVM), and 12 at once on a small machine is slower than
  8, not faster."
  (max 2 (min 8 (.availableProcessors (Runtime/getRuntime)))))

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

(defn install!
  "Wraps `clojure.test/test-ns` so a `^:parallel` namespace runs its
  tests concurrently. Idempotent.

  `test-ns` rather than `test-vars` is the seam: clojure.test is compiled
  with direct linking, so `test-ns`'s own call to `test-vars` cannot be
  intercepted, while the caller (Leiningen's generated form, `run-tests`
  users going through the var) does see a replaced `test-ns`. This
  mirrors clojure.test's own `test-ns` body: begin/end reports, a fresh
  report-counter ref, and the counters as the result."
  []
  (when-not (::installed (meta #'t/test-ns))
    (let [original t/test-ns]
      (alter-var-root
       #'t/test-ns
       (constantly
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
                @t/*report-counters*))))))
      (alter-meta! #'t/test-ns assoc ::installed true))))

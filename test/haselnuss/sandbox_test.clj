(ns haselnuss.sandbox-test
  "Pins how `haselnuss.sandbox/sci-call!` settles a result on the worker
  (TASK-211): every result is copied into plain data and measured there,
  under the timeout, so nothing a script made is realised, printed or
  dereferenced on the calling thread. `haselnuss.extensions.sidecar-test`,
  `.rules-test`, `haselnuss.library.generated-test` and
  `haselnuss.cli-test` cover the same through each loader."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.sandbox :as sandbox]
            [sci.core :as sci]
            [sci.impl.vars :as sci-vars]))

(defn- call
  "Evaluates `source` in a fresh fork through `sci-call!` as `kind`,
  returning `[:ok value]` or `[:threw type message]`, and giving up after
  30 s (`:hung`) so a regression fails instead of hanging the suite."
  ([source kind] (call source kind {}))
  ([source kind opts]
   (let [ctx (sandbox/fork)
         f (future (try [:ok (sandbox/sci-call! #(sci/eval-string* ctx source) "test call"
                                                (merge {:timeout-ms 2000} opts) kind)]
                        (catch Exception e [:threw (:type (ex-data e)) (ex-message e)])))
         result (deref f 30000 :hung)]
     (when (= :hung result) (future-cancel f))
     result)))

(def ^:private looping-to-string
  "(let [n (atom 0)] (reify Object (toString [_] (if (< (swap! n inc) 2) \"ok\" (loop [] (recur))))))")

(def ^:private looping-deref "(reify clojure.lang.IDeref (deref [_] (loop [] (recur))))")

(deftest ordinary-results-test
  (testing "plain data comes back equal, lazy seqs realised as lists"
    (is (= [:ok [1 {:a '(2 3 4)} #{:x} "s" \c 1.5 nil true 'sym]]
           (call "[1 {:a (map inc (range 1 4))} #{:x} \"s\" \\c 1.5 nil true 'sym]" :data))))
  (testing "metadata is dropped"
    (let [[_ v] (call "(with-meta {:a 1} {:line 99})" :data)]
      (is (= {:a 1} v))
      (is (nil? (meta v)))))
  (testing "a :spec keeps the functions, vars and regexes a file defines"
    (let [[status v] (call "(defn g [] 2) {:f (fn [] 1) :v #'g :re #\"a+\" :n 1}" :spec)]
      (is (= :ok status))
      (is (= 1 ((:f v))))
      (is (= 2 ((:v v))))
      (is (= "aa" (re-find (:re v) "baab")))))
  (testing "a fn is not plain data outside a :spec"
    (let [[status error-type message] (call "{:f (fn [] 1)}" :result)]
      (is (= :threw status))
      (is (= ::sandbox/unsupported-result error-type))
      (is (re-find #"test call's own output is not plain data: .* at \[:f\]" message)))))

(deftest host-records-test
  (testing "a :result keeps an emitter's own Rendered, copying its values"
    (doseq [r [(html/->Rendered "<p>x</p>") (latex/->Rendered "\\emph{x}")]]
      (let [copied (sandbox/plain-copy (with-meta r {:m 1}) 100 :result)]
        (is (= (class r) (class copied)))
        (is (= r copied))
        (is (nil? (meta copied))))))
  (testing "TASK-213: an unchanged one is kept as the very same instance, so an
            emitter can still recognise a Rendered its callback issued; a
            changed one is a new instance, which the emitter then refuses"
    (doseq [r [(html/->Rendered "<p>x</p>") (latex/->Rendered "\\emph{x}")]]
      (is (identical? r (sandbox/plain-copy r 100 :result)))
      (let [changed (assoc r :extra "y")
            copied (sandbox/plain-copy changed 100 :result)]
        (is (= changed copied))
        (is (not (identical? r copied))))))
  (testing "outside a :result a host record is copied as a plain map"
    (let [copied (sandbox/plain-copy (html/->Rendered "<p>") 100 :data)]
      (is (= {:html "<p>"} copied))
      (is (not (instance? clojure.lang.IRecord copied)))))
  (testing "a script's own defrecord, which may carry its own toString, is
            copied as a plain map rather than kept"
    (let [[status v] (call "(defrecord R [a] Object (toString [_] \"R!\")) (->R 1)" :result)]
      (is (= :ok status))
      (is (= {:a 1} v))
      (is (not (instance? clojure.lang.IRecord v))))))

(deftest unsafe-results-fail-on-the-worker-test
  (testing "an endless lazy value, a stateful toString reify and an IDeref
            reify, top-level or nested, each fail inside the timeout with
            an error naming the call, for every kind"
    (doseq [kind [:data :result :spec]
            [label source expected-type fragment]
            [["endless lazy" "(range)" ::sandbox/oversized-output "200000-character cap"]
             ["nested endless lazy" "{:a [(map identity (range))]}" ::sandbox/oversized-output "-character cap"]
             ["stateful toString" looping-to-string ::sandbox/unsupported-result "not plain data"]
             ["nested stateful toString" (str "[:div " looping-to-string "]") ::sandbox/unsupported-result
              "at [1]"]
             ["IDeref" (str "{:x " looping-deref "}") ::sandbox/unsupported-result "at [:x]"]
             ["atom" "{:x (atom 1)}" ::sandbox/unsupported-result "clojure.lang.Atom"]]]
      (let [start (System/nanoTime)
            [status actual-type message :as result] (call source kind)
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (is (= :threw status) (str kind " " label ": " (pr-str result)))
        (is (= expected-type actual-type) (str kind " " label))
        (is (re-find #"^test call" (str message)) (str kind " " label ": " message))
        (is (some-> message (.contains fragment)) (str kind " " label ": " message))
        (is (< elapsed-ms 10000) (str kind " " label " took " elapsed-ms "ms")))))
  (testing "a value that realises forever without growing times out"
    (is (= ::sandbox/timeout
           (second (call "[(map (fn [_] (loop [] (recur))) [1])]" :data {:timeout-ms 300})))))
  (testing "the size check counts printed characters, not values"
    (is (= ::sandbox/oversized-output
           (second (call "(apply str (repeat 1000 \"x\"))" :data {:output-limit 100}))))
    (is (= :ok (first (call "(apply str (repeat 1000 \"x\"))" :spec {:output-limit 100})))
        "a :spec is copied but not measured")))

;; --- TASK-214: nothing a script made leaves in an error or a :spec ----------

(defn- thrown
  "The `ex-info` `sci-call!` raises evaluating `source` in a fresh fork as
  `kind`, or nil when it returns."
  [source kind]
  (let [ctx (sandbox/fork)]
    (try (sandbox/sci-call! #(sci/eval-string* ctx source) "test call" {:timeout-ms 2000} kind)
         nil
         (catch clojure.lang.ExceptionInfo e e))))

(deftest failure-path-holds-copied-keys-test
  (testing "an ::unsupported-result's :path holds copies of the script's
            map keys, without their metadata"
    (let [e (thrown "{(with-meta 'k {:x (reify Object)}) (atom 1)}" :data)
          [k] (:path (ex-data e))]
      (is (= ::sandbox/unsupported-result (:type (ex-data e))))
      (is (= 'k k))
      (is (nil? (meta k))))))

(deftest spec-functions-lose-their-metadata-test
  (testing "a :spec keeps a function but drops its metadata, which could hold
            a reify"
    (let [[status v] (call "{:f (with-meta (fn [] 1) {:x (reify Object)})}" :spec)]
      (is (= :ok status))
      (is (= 1 ((:f v))))
      (is (nil? (meta (:f v)))))))

(deftest evaluation-error-cause-is-sanitised-test
  (testing "an ::evaluation-error's cause chain carries the script's messages
            but only keyword-keyed scalar ex-data, so printing it runs no
            script code"
    (let [e (thrown (str "(throw (ex-info \"outer\" {:n 1 :r (reify clojure.lang.IDeref (deref [_] (loop [] (recur))))}"
                         " (ex-info \"inner\" {\"k\" 2 :s \"ok\"})))")
                    :data)
          chain (take-while some? (iterate ex-cause e))
          printed (future (mapv (comp pr-str ex-data) chain))]
      (is (= ::sandbox/evaluation-error (:type (ex-data e))))
      (is (= "test call raised an error: outer" (ex-message e)))
      (is (not= :hung (deref printed 10000 :hung)))
      (is (= [{:n 1} {:s "ok"}] (mapv ex-data (rest chain))))
      (is (= ["outer" "inner"] (mapv ex-message (rest chain))))))
  (testing "a sci error keeps the location it carries"
    (let [e (thrown "\n\n(nope 1)" :data)
          located (some #(when (= :sci/error (:type (ex-data %))) %)
                        (take-while some? (iterate ex-cause e)))]
      (is (= 3 (:line (ex-data located))))))
  (testing "a host exception is rebuilt too, naming its class"
    (let [e (thrown "(defn f [] (f)) (f)" :data)
          chain (take-while some? (iterate ex-cause e))]
      (is (= "test call raised an error: java.lang.StackOverflowError" (ex-message e)))
      (is (some #(= "java.lang.StackOverflowError" (::sandbox/error-class (ex-data %))) chain))
      (is (every? #(instance? clojure.lang.ExceptionInfo %) chain))))
  (testing "a script's suppressed exceptions are not carried over, at any link,
            so printing the host error's stack trace finishes"
    (doseq [source ["(let [e (ex-info \"x\" {})]
                       (.addSuppressed e (ex-info \"y\" {:r (reify Object (toString [_] (loop [] (recur))))}))
                       (throw e))"
                    "(let [e (ex-info \"x\" {}) outer (ex-info \"outer\" {} e)]
                       (.addSuppressed e (ex-info \"y\" {:r (reify Object (toString [_] (loop [] (recur))))}))
                       (throw outer))"]]
      (let [e (thrown source :data)
            chain (take-while some? (iterate ex-cause e))
            printed (future (let [w (java.io.StringWriter.)]
                              (.printStackTrace ^Throwable e (java.io.PrintWriter. w))
                              (str w)))]
        (is (= ::sandbox/evaluation-error (:type (ex-data e))))
        (is (every? #(zero? (alength (.getSuppressed ^Throwable %))) chain))
        (is (string? (deref printed 10000 :hung)) "printStackTrace hung"))))
  (testing "a link that is not an ExceptionInfo is rebuilt as well, dropping
            what it carries -- a suppressed ex-info with a looping reify in
            its data, below and above a rebuilt one"
    (let [looping (reify Object (toString [_] (loop [] (recur))))
          host (doto (RuntimeException. "host")
                 (.addSuppressed (ex-info "y" {:r looping})))
          t (IllegalStateException. "top" (ex-info "mid" {:r looping} host))
          copy (#'sandbox/sanitize-error t 16)
          chain (take-while some? (iterate ex-cause copy))
          printed (future (let [w (java.io.StringWriter.)]
                            (.printStackTrace ^Throwable copy (java.io.PrintWriter. w))
                            (str w)))]
      (is (= ["top" "mid" "host"] (mapv ex-message chain)))
      (is (= ["java.lang.IllegalStateException" nil "java.lang.RuntimeException"]
             (mapv (comp ::sandbox/error-class ex-data) chain)))
      (is (every? #(zero? (alength (.getSuppressed ^Throwable %))) chain))
      (is (string? (deref printed 10000 :hung)) "printStackTrace hung"))))

;; --- TASK-218: workers are reused only when left clean ----------------------

(def ^:dynamic *leak*
  "A host dynamic var a test call binds and never unbinds."
  :root)

(defn- on-worker
  "Runs host `f` through `sci-call!` (`opts` merged over a 2 s timeout),
  returning `[thread result]` -- the worker `Thread` it ran on and its
  result, or `[thread :threw type]` when the call raised."
  ([f] (on-worker f {}))
  ([f opts]
   (let [t (atom nil)
         r (try (sandbox/sci-call! (fn [] (reset! t (Thread/currentThread)) (f))
                                   "reuse test" (merge {:timeout-ms 2000} opts))
                (catch clojure.lang.ExceptionInfo e [:threw (:type (ex-data e))]))]
     [@t r])))

(defn- ended?
  "True when `thread` ends within 5 s."
  [^Thread thread]
  (.join thread 5000)
  (not (.isAlive thread)))

(deftest worker-reuse-test
  (sandbox/shutdown-idle-workers!)
  (testing "consecutive clean calls run on the same daemon worker, named
            after the call"
    (let [[t1] (on-worker (fn [] (.getName (Thread/currentThread))))
          [t2 name2] (on-worker (fn [] (.getName (Thread/currentThread))))]
      (is (identical? t1 t2))
      (is (.isDaemon ^Thread t2))
      (is (= "haselnuss-sandbox-worker: reuse test" name2))))
  (testing "sandboxed evaluations reuse it too, and each still sees only its
            own fork"
    (let [a (sandbox/fork) b (sandbox/fork)]
      (is (= 1 (sandbox/sci-call! #(sci/eval-string* a "(def x 1) x") "a" {})))
      (is (re-find #"resolve symbol: x"
                   (try (sandbox/sci-call! #(sci/eval-string* b "x") "b" {})
                        (catch clojure.lang.ExceptionInfo e (ex-message (ex-cause e)))))))))

(deftest worker-is-discarded-unless-clean-test
  (doseq [[label f] [["a timeout" #(Thread/sleep 10000)]
                     ["an error" #(throw (RuntimeException. "boom"))]
                     ["a leaked host binding" #(do (push-thread-bindings {#'*leak* :leaked}) nil)]
                     ["a leaked sci binding" #(do (sci-vars/push-thread-bindings {sci/out (java.io.StringWriter.)})
                                                  nil)]
                     ["a set interrupt flag" #(do (.interrupt (Thread/currentThread)) nil)]]]
    (testing (str "after " label ", that worker ends and the next call runs
                   clean on another")
      (sandbox/shutdown-idle-workers!)
      (let [[t1] (on-worker f {:timeout-ms 300})
            ;; `sci-call!` itself binds exactly sci/out and sci/err.
            [t2 seen] (on-worker (fn [] [*leak*
                                         (.isInterrupted (Thread/currentThread))
                                         (= #{sci/out sci/err} (set (keys (sci-vars/get-thread-bindings))))]))]
        (is (some? t1))
        (is (not (identical? t1 t2)))
        (is (ended? t1))
        (is (= [:root false true] seen))))))

(deftest timeout-is-still-a-timeout-test
  (testing "a runaway sandboxed loop still times out, its worker ends, and the
            next call works"
    (sandbox/shutdown-idle-workers!)
    (let [ctx (sandbox/fork)
          t (atom nil)
          start (System/nanoTime)
          e (try (sandbox/sci-call! (fn [] (reset! t (Thread/currentThread))
                                      (sci/eval-string* ctx "(loop [] (recur))"))
                                    "spin" {:timeout-ms 300})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::sandbox/timeout (:type (ex-data e))))
      (is (< (/ (- (System/nanoTime) start) 1e6) 5000))
      ;; Only `Thread.stop` ends an interpreted loop, and it is gone from JDK
      ;; 20; there the worker is merely never reused (the daemon residual).
      (when (< (.feature (Runtime/version)) 20)
        (is (ended? @t) "Thread.stop ends it on JDK < 20"))
      (is (= 3 (sandbox/sci-call! #(sci/eval-string* ctx "(+ 1 2)") "after" {})))))
  (testing "calls finishing right at their deadline either time out or answer,
            and never hand a later call a dirty or abandoned worker"
    (dotimes [_ 40]
      (let [[_ r] (on-worker #(do (Thread/sleep 5) :ok) {:timeout-ms 5})]
        (is (contains? #{:ok [:threw ::sandbox/timeout]} r))))
    (is (= :ok (second (on-worker (constantly :ok)))))))

(deftest caller-wins-the-race-test
  (testing "a worker the caller gave up on that then returns normally (the
            JDK 20+ path, forced here by swallowing the interrupt and the
            ThreadDeath) is never reused, and its late answer goes nowhere"
    (sandbox/shutdown-idle-workers!)
    (let [late (atom nil)
          [t r] (on-worker (fn []
                             (dotimes [_ 3] (try (Thread/sleep 300) (catch Throwable _ nil)))
                             (reset! late :returned)
                             :late)
                           {:timeout-ms 100})
          [t2 r2] (on-worker (constantly :next))]
      (is (= [:threw ::sandbox/timeout] r))
      (is (= :next r2))
      (is (not (identical? t t2)))
      (is (ended? t))
      (is (= :returned @late) "the worker did return normally")
      (let [[t3] (on-worker (constantly nil))]
        (is (not (identical? t t3)) "nor did it enter the pool later")))))

(deftest failing-bookkeeping-never-hangs-test
  (testing "a throw in the worker's own bookkeeping after it won the race
            still hands the caller its result, within the timeout, and
            retires that worker"
    (sandbox/shutdown-idle-workers!)
    (let [f (future (with-redefs [sandbox/release! (fn [_] (throw (OutOfMemoryError. "test")))]
                      (on-worker (constantly :ok) {:timeout-ms 500})))
          [t r :as res] (deref f 3000 :hung)]
      (is (not= :hung res))
      (is (= :ok r))
      (is (ended? t))
      (is (= :ok (second (on-worker (constantly :ok))))))))

(deftest nested-calls-test
  (testing "a sandboxed call made from inside one takes another worker rather
            than waiting for its own"
    (let [inner (atom nil)
          [outer r] (on-worker #(let [[t v] (on-worker (constantly :inner))]
                                  (reset! inner t)
                                  v))]
      (is (= :inner r))
      (is (some? @inner))
      (is (not (identical? outer @inner))))))

(deftest idle-workers-end-test
  (testing "shutdown-idle-workers! ends an idle worker at once"
    (let [[t] (on-worker (constantly nil))]
      (sandbox/shutdown-idle-workers!)
      (is (ended? t))))
  (testing "an idle worker ends on its own after its keepalive"
    (let [[t] (on-worker (constantly nil))]
      (is (.isAlive ^Thread t))
      (is (ended? t)))))

(ns haselnuss.extensions.sidecar-test
  "Fixtures for `haselnuss.extensions.sidecar` (TASK-90), the sandboxed
  loader behind `--extensions`/`--trusted-extensions`, one group per
  acceptance criterion. `haselnuss.cli-test` covers the same mechanism
  through the real CLI flags end to end (AC #13's worked example, and
  AC #11's trust gating through the actual `render-directive` path);
  this file exercises `load-extensions` directly, against the loading
  mechanism and its own runtime bounds in isolation.

  Every refusal test below is grounded in something concretely on THIS
  project's own classpath or filesystem (`haselnuss.parser`, this
  process's own real files), not a strawman, mirroring how this
  namespace's own docstring records what was actually verified live
  before being relied on."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.extensions.sidecar :as sidecar]
            [haselnuss.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- scratch-dir!
  "A fresh temp directory this file's own sidecar fixtures are written
  into, one per test -- mirrors `haselnuss.cli-test/fixture-dir!`'s own
  temp-directory idiom."
  []
  (str (Files/createTempDirectory "haselnuss-sidecar-test" (make-array FileAttribute 0))))

(defn- write-extension!
  "Writes `content` as `filename` inside `dir`, returning its path -- one
  sidecar `.clj` file, `spit` ad hoc exactly the way `cli_test.clj`'s own
  `--overlay` tests build their fixtures, rather than a committed file
  under `test/fixtures/`."
  [dir filename content]
  (let [f (io/file dir filename)]
    (spit f content)
    (str f)))

(defn- outcome
  "Runs `thunk`, returning `[:ok value]` or `[:threw type message]` --
  `type` the thrown `ex-info`'s own `:type`, so a test can assert on it
  directly rather than pattern-matching a message string."
  [thunk]
  (try [:ok (thunk)]
       (catch Exception e [:threw (:type (ex-data e)) (ex-message e)])))

(deftest ac1-load-one-extension-test
  (testing "AC #1: a sidecar file defining a directive's HTML and LaTeX
            rendering loads through load-extensions, with no host
            recompile -- this file is read and evaluated at TEST RUN
            TIME, the same as it would be at BUILD time"
    (let [dir (scratch-dir!)
          path (write-extension! dir "callout.clj"
                                 (str "{:name \"callout\"\n"
                                      " :emit {:html (fn [directive ctx] "
                                      "[:div {:class \"callout\"} \"hi\"])\n"
                                      "        :latex (fn [directive ctx] \"CALLOUT\")}}"))
          [entry] (sidecar/load-extensions [path])]
      (is (= "callout" (:name entry)))
      (is (false? (:trusted? entry)) "trust defaults to off (AC #11) even unasked")
      (is (true? (registry/v2? (get-in entry [:emit :html])))
          "every sidecar-authored renderer speaks TASK-89's v2 contract")
      (is (true? (registry/v2? (get-in entry [:emit :latex]))))
      (is (= [:div {:class "callout"} "hi"] ((get-in entry [:emit :html]) {} {})))
      (is (= "CALLOUT" ((get-in entry [:emit :latex]) {} {}))))))

(deftest ac1-multiple-extensions-in-one-file-test
  (testing "AC #1: a file's last form may be a VECTOR of extension maps,
            defining several directives at once"
    (let [dir (scratch-dir!)
          path (write-extension! dir "many.clj"
                                 (str "[{:name \"a\" :emit {:html (fn [d c] :a)}}\n"
                                      " {:name \"b\" :emit {:html (fn [d c] :b)}}]"))
          entries (sidecar/load-extensions [path])]
      (is (= #{"a" "b"} (set (map :name entries))))
      (is (= :a ((get-in (first (filter #(= "a" (:name %)) entries)) [:emit :html]) {} {})))
      (is (= :b ((get-in (first (filter #(= "b" (:name %)) entries)) [:emit :html]) {} {}))))))

(deftest ac1-lazy-seq-of-extensions-test
  (testing "AC #1: the file's last form may also be a LAZY SEQ of
            extension maps -- (for [n [...]] {...}), the single most
            idiomatic way a Clojure author reaches for 'define several
            directives at once', not only a literal vector (found by
            review: a stricter vector? check rejected this, even though
            haselnuss.registry/register-all, the actual downstream
            consumer, is deliberately collection-agnostic)"
    (let [dir (scratch-dir!)
          path (write-extension! dir "generated.clj"
                                 (str "(for [n [\"a\" \"b\" \"c\"]]\n"
                                      "  {:name n :emit {:html (fn [d c] n)}})"))
          entries (sidecar/load-extensions [path])]
      (is (= #{"a" "b" "c"} (set (map :name entries))))
      (is (= "b" ((get-in (first (filter #(= "b" (:name %)) entries)) [:emit :html]) {} {})))))
  (testing "an ordinary (map ...) result works the same way"
    (let [dir (scratch-dir!)
          path (write-extension! dir "mapped.clj"
                                 "(map (fn [n] {:name n :emit {:html (fn [d c] n)}}) [\"x\" \"y\"])")
          entries (sidecar/load-extensions [path])]
      (is (= #{"x" "y"} (set (map :name entries))))))
  (testing "a SET of extension maps works too -- register-all's own
            tolerance extends to any seqable, sets included"
    (let [dir (scratch-dir!)
          path (write-extension! dir "setted.clj"
                                 (str "#{{:name \"p\" :emit {:html (fn [d c] :p)}}\n"
                                      "  {:name \"q\" :emit {:html (fn [d c] :q)}}}"))
          entries (sidecar/load-extensions [path])]
      (is (= #{"p" "q"} (set (map :name entries))))))
  (testing "widening acceptance did not widen what still counts as
            malformed -- a collection with a non-map element is still
            refused, naming the file"
    (let [dir (scratch-dir!)
          path (write-extension! dir "mixed.clj"
                                 "[{:name \"a\" :emit {:html (fn [d c] 1)}} 42]")
          [status error-type] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.sidecar/malformed-extension error-type))))
  (testing "regression guard: a lazy seq is fully realized ON THE SAME
            timeout-bounded worker thread that produced it, not left for
            a later caller to realize outside that bound -- an infinite
            (for [n (range)] ...) is stopped by the file's own
            :timeout-ms exactly like any other non-terminating sandboxed
            call (AC #7), rather than the lazy realization escaping the
            bound entirely because nothing forced it until afterward"
    (let [dir (scratch-dir!)
          path (write-extension! dir "infinite.clj"
                                 "(for [n (range)] {:name (str n) :emit {:html (fn [d c] n)}})")
          start (System/currentTimeMillis)
          [status error-type] (outcome #(sidecar/load-extensions [path] {:timeout-ms 200}))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= :threw status))
      ;; Since TASK-211 the worker's copy of the result counts every value
      ;; against the output cap, so an endless seq of cheap maps can hit
      ;; that budget before the deadline; either way it stops on the worker.
      (is (contains? #{:haselnuss.sandbox/timeout :haselnuss.sandbox/oversized-output} error-type))
      (is (< elapsed 2000) "resolves close to the configured bound, not after realizing forever")))
  (testing "regression guard: realizing a lazy seq that itself PRINTS is
            captured the same way any other sandboxed output is (AC #9),
            not left to leak once the seq's elements are forced -- found
            by review: an earlier fix nested force-realized OUTSIDE
            sci/binding's own dynamic extent
            ((force-realized (sci/binding [...] (f))) instead of
            (sci/binding [...] (force-realized (f)))), which realized a
            lazy seq correctly bounded by the timeout but with sci/out/
            sci/err already unbound, throwing sci's own internal
            ClassCastException on a SciUnbound value rather than loading
            cleanly -- this pins the corrected nesting"
    (let [dir (scratch-dir!)
          path (write-extension! dir "printy.clj"
                                 (str "(for [n [\"a\" \"b\"]]\n"
                                      "  (do (println \"defining\" n)\n"
                                      "      {:name n :emit {:html (fn [d c] n)}}))"))
          real-out (java.io.StringWriter.)
          entries (binding [*out* real-out] (sidecar/load-extensions [path]))]
      (is (= #{"a" "b"} (set (map :name entries))) "loads successfully rather than throwing")
      (is (not (str/includes? (str real-out) "defining"))
          "the for-comprehension's own println never reaches the real *out*"))))

(deftest wrap-fn-lazy-realization-test
  (testing "the same force-realized/sci/binding/ctx-store nesting
            applies to a LATER renderer/lower-rule call (wrap-fn), not
            only the file-load step ac1-lazy-seq-of-extensions-test
            covers -- a renderer returning a lazy fragment (a bare
            (map ...), not wrapped in vec/doall by its own author) whose
            OWN realization needs sci.ctx-store/*ctx* (calls require,
            same as haselnuss.extensions.sidecar-test's own AC #5/#6
            cases) must still work, not just be bounded and captured.
            Found by review while checking for further instances of the
            same ordering mistake: wrap-fn's own thunk established
            ctx-store/with-ctx, but sci-call!'s force-realized wrapped
            the WHOLE thunk's call from outside it -- correct for
            sci/binding (sci-call!'s own scope), wrong for ctx-store
            (a scope the thunk opens and closes internally, one layer
            deeper) -- confirmed live before fixing: this exact scenario
            threw sci's own internal 'No context found in:
            sci.ctx-store/*ctx*' rather than resolving. Fixed by also
            calling force-realized INSIDE wrap-fn's own with-ctx body"
    (let [dir (scratch-dir!)
          path (write-extension! dir "lazy-renderer.clj"
                                 (str "{:name \"w\" :emit {:html (fn [directive ctx]\n"
                                      "  (map (fn [n] (require (symbol \"clojure.set\"))\n"
                                      "               (clojure.set/union #{n} #{n}))\n"
                                      "       [1 2 3]))}}"))
          [entry] (sidecar/load-extensions [path])]
      (is (= (list #{1} #{2} #{3}) ((get-in entry [:emit :html]) {} {})))))
  (testing "and its own realization is captured too, simultaneously with
            the require above working -- both properties this namespace
            promises have to hold together, not just separately"
    (let [dir (scratch-dir!)
          path (write-extension! dir "combo-renderer.clj"
                                 (str "{:name \"w\" :emit {:html (fn [directive ctx]\n"
                                      "  (map (fn [n] (println \"realizing\" n)\n"
                                      "               (require (symbol \"clojure.set\"))\n"
                                      "               n)\n"
                                      "       [1 2 3]))}}"))
          [entry] (sidecar/load-extensions [path])
          real-out (java.io.StringWriter.)
          result (binding [*out* real-out] ((get-in entry [:emit :html]) {} {}))]
      (is (= (list 1 2 3) result))
      (is (not (str/includes? (str real-out) "realizing"))))))

(deftest missing-extension-file-test
  (testing "a --extensions path naming no file is a hard, clearly-named
            failure, mirroring haselnuss.cli/load-overlays!'s own
            established convention for --overlay"
    (let [dir (scratch-dir!)
          path (str (io/file dir "nowhere.clj"))
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.sidecar/missing-extension-file error-type))
      (is (str/includes? message path)))))

(deftest ac10-malformed-extension-test
  (testing "AC #10: a file whose last form is not a map or a vector of
            maps is rejected, naming the file"
    (let [dir (scratch-dir!)
          path (write-extension! dir "not-a-map.clj" "42")
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.sidecar/malformed-extension error-type))
      (is (str/includes? message path))
      (is (str/includes? message "42"))))
  (testing "AC #10: a missing :name is rejected"
    (let [dir (scratch-dir!)
          path (write-extension! dir "no-name.clj" "{:emit {:html (fn [d c] 1)}}")
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.sidecar/malformed-extension error-type))
      (is (str/includes? message ":name"))))
  (testing "AC #10: a blank :name is rejected the same way"
    (let [dir (scratch-dir!)
          path (write-extension! dir "blank-name.clj" "{:name \"\" :emit {:html (fn [d c] 1)}}")]
      (is (= :threw (first (outcome #(sidecar/load-extensions [path])))))))
  (testing "AC #10: a non-callable :emit value is rejected, naming the
            directive and the target"
    (let [dir (scratch-dir!)
          path (write-extension! dir "bad-emit.clj" "{:name \"x\" :emit {:html \"not-a-fn\"}}")
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.sidecar/malformed-extension error-type))
      (is (str/includes? message "\"x\""))
      (is (str/includes? message ":html"))))
  (testing "AC #10: a non-callable :lower value is rejected"
    (let [dir (scratch-dir!)
          path (write-extension! dir "bad-lower.clj" "{:name \"x\" :lower \"nope\"}")]
      (is (= :threw (first (outcome #(sidecar/load-extensions [path]))))))))

(deftest ac3-host-interop-refused-test
  (testing "AC #3: host interop -- a bare System/exit reference -- is
            refused. sci's own eager symbol resolution catches this at
            LOAD time here, before the renderer is ever called, which is
            a stronger guarantee than AC #3 asks for, not a weaker one"
    (let [dir (scratch-dir!)
          path (write-extension! dir "interop.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] (System/exit 0))}}")
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "System/exit"))))
  (testing "AC #3, a different shape: constructing a java.io.File is
            refused the same way"
    (let [dir (scratch-dir!)
          path (write-extension! dir "ctor.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] (java.io.File. \".\"))}}")]
      (is (= :threw (first (outcome #(sidecar/load-extensions [path]))))))))

(deftest ac4-file-read-refused-test
  (testing "AC #4: slurp is not a bindable symbol in this sandbox at
            all -- refused at load time, naming the file"
    (let [dir (scratch-dir!)
          path (write-extension! dir "readfile.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] (slurp \"/etc/hostname\"))}}")
          [status error-type message] (outcome #(sidecar/load-extensions [path]))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "slurp")))))

(deftest ac5-process-execution-refused-test
  (testing "AC #5: clojure.java.shell is a real namespace on THIS
            project's own classpath (haselnuss.cli-test itself requires
            it, for pdflatex), refused via :load-fn returning nil for
            anything not already known to the sandbox. The namespace
            symbol is built at RUNTIME (`(symbol \"...\")`), not written
            as a literal reference, so this specifically exercises the
            CALL-TIME half of the bound (wrap-fn), not sci's separate
            eager symbol-resolution check AC #3/#4 already cover"
    (let [dir (scratch-dir!)
          path (write-extension! dir "procexec.clj"
                                 (str "{:name \"x\" :emit {:html (fn [d c] "
                                      "(require (symbol \"clojure.java.shell\")) :ran)}}"))
          [entry] (sidecar/load-extensions [path])
          [status error-type message] (outcome #((get-in entry [:emit :html]) {} {}))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "clojure.java.shell")))))

(deftest ac6-classpath-require-refused-test
  (testing "AC #6: requiring a real namespace on this project's own
            classpath (haselnuss.parser) is refused, at call time"
    (let [dir (scratch-dir!)
          path (write-extension! dir "reqclasspath.clj"
                                 (str "{:name \"x\" :emit {:html (fn [d c] "
                                      "(require (symbol \"haselnuss.parser\")) :ran)}}"))
          [entry] (sidecar/load-extensions [path])
          [status error-type message] (outcome #((get-in entry [:emit :html]) {} {}))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "haselnuss.parser"))))
  (testing "AC #6, positive control: a namespace sci bundles itself
            (clojure.set) is NOT reached via this project's classpath at
            all, so it stays usable -- named here so the refusal above is
            read as \"this project's own code is unreachable\", not
            \"nothing named require works\""
    (let [dir (scratch-dir!)
          path (write-extension! dir "reqset.clj"
                                 (str "{:name \"x\" :emit {:html (fn [d c] "
                                      "(require 'clojure.set) (clojure.set/union #{1} #{2}))}}"))
          [entry] (sidecar/load-extensions [path])]
      (is (= #{1 2} ((get-in entry [:emit :html]) {} {}))))))

(deftest ac7-non-terminating-code-stopped-test
  (testing "AC #7: an interpreted infinite loop does not hang the build
            -- bounded by a short, test-local :timeout-ms rather than
            this namespace's own (generous) default, so this test itself
            resolves quickly. sci ships no interpreter-level interrupt
            mechanism at all (verified directly against its own source,
            recorded in haselnuss.extensions.sidecar's own docstring), so
            the abandoned worker thread keeps looping in the background
            after this test moves on -- an accepted, documented residual,
            not something this test can or should assert against"
    (let [dir (scratch-dir!)
          path (write-extension! dir "infloop.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] (loop [] (recur)))}}")
          [entry] (sidecar/load-extensions [path] {:timeout-ms 200})
          ;; TASK-147: the worker is stopped on JDK 17, so its name has to be
          ;; sampled while it spins, not looked for afterwards.
          done? (atom false)
          matches? (fn [n] (and (str/includes? n "haselnuss-sandbox-worker")
                                (str/includes? n path)))
          start (System/currentTimeMillis)
          sampler (future (loop []
                            (cond
                              (some matches? (map #(.getName ^Thread %)
                                                  (.keySet (Thread/getAllStackTraces)))) true
                              @done? false
                              :else (do (Thread/sleep 1) (recur)))))
          [status error-type message] (try (outcome #((get-in entry [:emit :html]) {} {}))
                                           (finally (reset! done? true)))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/timeout error-type))
      (is (str/includes? message "200ms"))
      (is (< elapsed 2000) "resolves close to the configured bound, not the 5s default")
      (testing "the abandoned worker thread this leaves running (see this
                test's own docstring) is identifiable, not a bare
                Thread-N -- named after what it was doing (found by
                review: debugging a CPU-pegged process via top -H/jstack
                needs exactly this, and the thread was unnamed before)"
        (is @sampler)))))

(deftest ac8-oversized-output-refused-test
  (testing "AC #8: a renderer's own returned output, over a small
            test-local :output-limit, is refused -- checked against the
            ACTUAL rendered value, not the extension-spec map's own
            unrelated pr-str (which embeds an opaque, memory-address-
            bearing function object print no author wrote and no cap
            should be measuring)"
    (let [dir (scratch-dir!)
          path (write-extension! dir "bigout.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] (apply str (repeat 1000 \"x\")))}}")
          [entry] (sidecar/load-extensions [path] {:output-limit 100})
          [status error-type message] (outcome #((get-in entry [:emit :html]) {} {}))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/oversized-output error-type))
      (is (str/includes? message "100-character cap"))))
  (testing "a normal-sized output, well under the default cap, succeeds"
    (let [dir (scratch-dir!)
          path (write-extension! dir "smallout.clj"
                                 "{:name \"x\" :emit {:html (fn [d c] \"a modest string\")}}")
          [entry] (sidecar/load-extensions [path])]
      (is (= "a modest string" ((get-in entry [:emit :html]) {} {}))))))

(deftest ac9-output-captured-test
  (testing "AC #9: a renderer's own println does not reach this
            process's real stdout -- captured into a throwaway
            StringWriter and discarded instead"
    (let [dir (scratch-dir!)
          path (write-extension! dir "noisy.clj"
                                 (str "{:name \"x\" :emit {:html (fn [d c] "
                                      "(println \"SHOULD NOT LEAK\") \"quiet result\")}}"))
          [entry] (sidecar/load-extensions [path])
          real-out (java.io.StringWriter.)
          result (binding [*out* real-out] ((get-in entry [:emit :html]) {} {}))]
      (is (= "quiet result" result))
      (is (not (str/includes? (str real-out) "SHOULD NOT LEAK"))))))

(deftest ac11-trust-gates-raw-test
  (testing "AC #11: :trusted? defaults to false, and is what an
            emitter's own :trusted? ctx entry has to be for a [:raw ...]
            node to be honored -- this namespace only constructs the
            NODE (haselnuss.ext/raw); whether it is spliced verbatim is
            entirely haselnuss.emit.html/latex's own render-directive
            decision (haselnuss.cli-test covers that half through the
            real emitter). What this namespace owns is that :trusted? is
            never the sidecar file's own to set"
    (let [dir (scratch-dir!)
          path (write-extension! dir "widget.clj"
                                 (str "{:name \"widget\" :emit {:latex "
                                      "(fn [d c] (haselnuss.ext/raw \"verbatim\"))}}"))]
      (is (false? (:trusted? (first (sidecar/load-extensions [path])))))
      (is (true? (:trusted? (first (sidecar/load-extensions [path] {:trusted? true})))))))
  (testing "a sidecar file's own :trusted?/:trusted claim is silently
            overridden by the CLI's own decision, never honored -- a
            sidecar cannot elevate its own trust"
    (let [dir (scratch-dir!)
          path (write-extension! dir "self-elevate.clj"
                                 "{:name \"x\" :trusted? true :emit {:html (fn [d c] 1)}}")]
      (is (false? (:trusted? (first (sidecar/load-extensions [path] {:trusted? false}))))
          "the sidecar asked for :trusted? true; the CLI said false, and false wins"))))

(deftest ac2-isolation-across-loads-test
  (testing "AC #2: two SEPARATE load-extensions calls against the same
            sidecar file -- standing in for two separate document builds
            in the same process -- do not share state. A def'd atom
            inside the file starts fresh every call, and neither
            observes the other's mutations"
    (let [dir (scratch-dir!)
          path (write-extension! dir "stateful.clj"
                                 (str "(def counter (atom 0))\n"
                                      "{:name \"x\" :emit {:html (fn [d c] (swap! counter inc))}}"))
          [entry-a] (sidecar/load-extensions [path])
          a1 ((get-in entry-a [:emit :html]) {} {})
          a2 ((get-in entry-a [:emit :html]) {} {})
          [entry-b] (sidecar/load-extensions [path])
          b1 ((get-in entry-b [:emit :html]) {} {})]
      (is (= [1 2] [a1 a2]) "within one load, state accumulates normally")
      (is (= 1 b1) "a fresh load starts over -- it never sees load A's 2"))))

(deftest renderer-composes-through-ctx-callbacks-test
  (testing "a sidecar renderer can call ctx's own :render-blocks (the
            SAME callback a host-defined v2 renderer gets, TASK-89 AC
            #2) -- an ordinary host closure handed into the sandbox as
            plain data, called from interpreted code with no interop
            needed at all, since invoking an already-in-hand function
            value is not host interop"
    (let [dir (scratch-dir!)
          path (write-extension! dir "compose.clj"
                                 (str "{:name \"callout\"\n"
                                      " :emit {:html (fn [directive ctx] "
                                      "[:div {:class \"callout\"} ((:render-blocks ctx) (:blocks directive))])}}"))
          [entry] (sidecar/load-extensions [path])
          fake-ctx {:render-blocks (fn [blocks] (str "RENDERED:" (count blocks)))}
          directive {:blocks [{:t :para} {:t :para}]}]
      (is (= [:div {:class "callout"} "RENDERED:2"]
             ((get-in entry [:emit :html]) directive fake-ctx))))))

(deftest sci-var-backed-renderer-test
  (testing "a sidecar author writing #'render-html -- a Var reference,
            this codebase's own idiom noted at TASK-24 finding #4, and a
            completely natural thing for a Clojure author to reach for --
            works exactly like a bare function reference. This is a
            sci.lang.Var, a different class from clojure.lang.Var, which
            is what haselnuss.registry/renderer-v2's IReference check
            (TASK-90) exists to keep working"
    (let [dir (scratch-dir!)
          path (write-extension! dir "varstyle.clj"
                                 (str "(defn render-html [directive ctx] [:p \"var-backed\"])\n"
                                      "{:name \"callout\" :emit {:html #'render-html}}"))
          [entry] (sidecar/load-extensions [path])]
      (is (true? (registry/v2? (get-in entry [:emit :html]))))
      (is (= [:p "var-backed"] ((get-in entry [:emit :html]) {} {}))))))

(deftest kind-passes-through-and-lower-is-wrapped-test
  (testing ":kind is plain data (a LabelLexicon merge-fragment) and
            passes through untouched -- nothing to wrap"
    (let [dir (scratch-dir!)
          path (write-extension! dir "kinded.clj"
                                 (str "{:name \"chart\" :kind {:cot {:counter :global "
                                      ":words {\"en\" {:singular \"Chart\"}}}}}"))
          [entry] (sidecar/load-extensions [path])]
      (is (= {:cot {:counter :global :words {"en" {:singular "Chart"}}}} (:kind entry)))))
  (testing ":lower is protected by the same bound :emit functions are --
            a non-terminating :lower rule times out rather than hanging"
    (let [dir (scratch-dir!)
          path (write-extension! dir "lower.clj"
                                 "{:name \"x\" :lower (fn [d t] (loop [] (recur)))}")
          [entry] (sidecar/load-extensions [path] {:timeout-ms 200})
          [status error-type] (outcome #((:lower entry) {} :latex))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/timeout error-type)))))

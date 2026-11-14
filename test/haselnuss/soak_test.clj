(ns haselnuss.soak-test
  "The soak driver `haselnuss.soak` (TASK-245), a few cycles on the JVM.
  The real soak -- 10,000 cycles in the native image -- is minutes long
  and needs its own native build, so it is `bin/soak`, run by hand and
  recorded in doc-11, not part of `lein test`. This keeps the driver
  itself from rotting between those runs: it still drives every phase of
  a cycle, and the graph it ends with still equals the post-scan graph.
  The heap verdict is NOT asserted here: over a handful of cycles it
  measures noise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.soak :as soak]))

(deftest refresh-soak-driver-test
  (let [lines (atom [])
        result (soak/soak-refresh "test/fixtures/libraries" 8 2 #(swap! lines conj %))]
    (testing "two full rounds of the four phases bring the graph back to the post-scan graph"
      (is (= 8 (:cycles result)))
      (is (:same-graph? result)))
    (testing "the soak library is scanned with the fixture libraries"
      (is (< 20 (:books result))))
    (testing "the heap is sampled in between and reported"
      (is (= [4] (mapv first (:samples result))))
      (is (pos? (:post-scan result)))
      (is (some #(str/starts-with? % "final heap after full GC: ") @lines)))))

(deftest refresh-soak-detects-a-changed-graph-test
  (testing "a cycle count that leaves an edit in place ends on a different
            graph, so `:same-graph?` is not vacuously true"
    (is (false? (:same-graph? (soak/soak-refresh "test/fixtures/libraries" 1 1 (fn [_])))))))

(deftest with-book-bench-test
  (let [result (soak/bench-with-book 50 20 (fn [_]))]
    (is (= 50 (:books result)))
    (is (<= 0 (:median-ms result) (:p95-ms result) (:max-ms result)))))

(deftest soak-profile-keeps-the-shipped-jar-test
  (testing "the :soak uberjar lands in target/uberjar/ beside the shipped one
            (Leiningen names it from the project merged with :uberjar), so it
            needs a name of its own and must not auto-clean that folder: with
            :auto-clean on, `lein with-profile +soak uberjar` deleted
            target/uberjar/haselnuss.jar. Pinned on the profile rather than by
            building, which takes a minute; verified by hand in TASK-245"
    (let [[_ _ _ & kvs] (read-string (slurp "project.clj"))
          soak (get-in (apply hash-map kvs) [:profiles :soak])]
      (is (false? (:auto-clean soak)))
      (is (= "haselnuss-soak.jar" (:uberjar-name soak)))
      (is (= ["dev"] (:source-paths soak))))))

(deftest usage-test
  (is (= 2 (binding [*err* (java.io.StringWriter.)] (soak/run ["refresh" "x" "4" "1" "no-such-backend"]))))
  (is (= 2 (binding [*err* (java.io.StringWriter.)] (soak/run ["nope"]))))
  (is (= 2 (binding [*err* (java.io.StringWriter.)] (soak/run ["refresh"])))))

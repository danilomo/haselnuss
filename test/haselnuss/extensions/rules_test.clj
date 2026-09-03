(ns haselnuss.extensions.rules-test
  "Fixtures for `haselnuss.extensions.rules` (TASK-103), the sandboxed
  loader behind `--transform`: mirrors `haselnuss.extensions.sidecar-
  test`'s own shape, since it is the SAME sandbox reached from a
  differently-shaped sidecar file (`haselnuss.sandbox`'s own docstring,
  ISOLATION) -- the interop/file/classpath/timeout/output-cap runtime-
  bound tests below (AC #5/#6) each mirror one of `haselnuss.extensions.
  sidecar-test`'s own, against the identical, shared `haselnuss.sandbox/
  sci-call!`/`wrap-fn`, rather than re-deriving that coverage from
  scratch; the exhaustive per-mechanism variations (every host-interop
  shape, every lazy-seq/`ctx-store` edge case) stay pinned there, once,
  not duplicated here. What this file covers on its own is what is
  specific to a RULE sidecar file: the rule shape itself, `load-
  transforms`' own malformed/missing-file refusals (AC #8), and
  `:ctx`-sharing between two separate `load-transforms` calls (AC #4).
  `haselnuss.cli-test` covers the same mechanism through the real
  `--transform` flag end to end, composed with `haselnuss.transform`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.extensions.rules :as rules]
            [haselnuss.sandbox :as sandbox])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- scratch-dir!
  "A fresh temp directory this file's own sidecar fixtures are written
  into, one per test -- mirrors `haselnuss.extensions.sidecar-test`'s
  own temp-directory idiom."
  []
  (str (Files/createTempDirectory "haselnuss-rules-test" (make-array FileAttribute 0))))

(defn- write-rule-file!
  [dir filename content]
  (let [f (io/file dir filename)]
    (spit f content)
    (str f)))

(defn- outcome
  "Runs `thunk`, returning `[:ok value]` or `[:threw type message]` --
  `type` the thrown `ex-info`'s own `:type`, mirroring `haselnuss.
  extensions.sidecar-test`'s own helper."
  [thunk]
  (try [:ok (thunk)]
       (catch Exception e [:threw (:type (ex-data e)) (ex-message e)])))

(def ^:private minimal-node {:t :section :level 1 :heading [] :blocks [] :attr {:classes [] :props {}}})

(deftest ac1-load-one-rule-test
  (testing "AC #1: a sidecar file whose last form is one {:select
            :transform} map loads through load-transforms, with :select/
            :name/:scope passed through untouched and :transform wrapped
            (still callable with the same result, once extracted)"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "upcase.clj"
                                 (str "{:name \"upcase\" :select :section "
                                      ":transform (fn [node ctx] node)}"))
          [rule] (rules/load-transforms [path])]
      (is (= "upcase" (:name rule)))
      (is (= :section (:select rule)))
      (is (= minimal-node ((:transform rule) minimal-node {}))))))

(deftest ac1-multiple-rules-in-one-file-test
  (testing "AC #1: a file's last form may be a VECTOR of rule maps,
            applied in that same declaration order once handed to
            haselnuss.transform (this loader's own job stops at loading
            them in file order; transform-document's own docstring
            covers the ordering guarantee itself)"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "many.clj"
                                 (str "[{:name \"a\" :select :section :transform (fn [n c] :a)}\n"
                                      " {:name \"b\" :select :para :transform (fn [n c] :b)}]"))
          rules (rules/load-transforms [path])]
      (is (= ["a" "b"] (map :name rules)))
      (is (= :a ((:transform (first rules)) nil nil)))
      (is (= :b ((:transform (second rules)) nil nil))))))

(deftest ac1-lazy-seq-and-set-of-rules-test
  (testing "AC #1: a lazy seq of rules, the idiomatic (for [...] ...)
            shape, works the same as a literal vector"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "generated.clj"
                                 (str "(for [n [\"a\" \"b\" \"c\"]]\n"
                                      "  {:name n :select :section :transform (fn [nd c] n)})"))
          rules (rules/load-transforms [path])]
      (is (= #{"a" "b" "c"} (set (map :name rules))))))
  (testing "and a set of rule maps works too"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "setted.clj"
                                 (str "#{{:name \"p\" :select :section :transform (fn [n c] :p)}\n"
                                      "  {:name \"q\" :select :para :transform (fn [n c] :q)}}"))
          rules (rules/load-transforms [path])]
      (is (= #{"p" "q"} (set (map :name rules)))))))

(deftest missing-transform-file-test
  (testing "a --transform path naming no file is a hard, clearly-named
            failure, mirroring --overlay/--extensions' own established
            convention"
    (let [dir (scratch-dir!)
          path (str (io/file dir "nowhere.clj"))
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/missing-transform-file error-type))
      (is (str/includes? message path)))))

(deftest ac8-malformed-rule-test
  (testing "AC #8: a file whose last form is not a rule map or a
            collection of them is refused, naming the file"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "not-a-map.clj" "42")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message path))
      (is (str/includes? message "42"))))
  (testing "AC #8: a rule with no :select is refused, naming the rule"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "no-select.clj" "{:transform (fn [n c] n)}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message ":select"))))
  (testing "AC #8: a :select that is none of haselnuss.select's own four
            recognized shapes (here, a bare string) is refused at LOAD
            time rather than only failing later, the first time the rule
            actually runs"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "bad-select.clj"
                                 "{:select \"section\" :transform (fn [n c] n)}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message ":select"))))
  (testing "AC #8: a rule with no :transform is refused, naming the rule"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "no-transform.clj" "{:select :section}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message ":transform"))))
  (testing "AC #8: a non-callable :transform value is refused"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "bad-transform.clj"
                                 "{:select :section :transform \"nope\"}")
          [status error-type] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))))
  (testing "AC #8: a non-string :name is refused"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "bad-name.clj"
                                 "{:name 42 :select :section :transform (fn [n c] n)}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message ":name"))))
  (testing "AC #8: a non-set :scope is refused"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "bad-scope.clj"
                                 "{:select :section :scope [:not-a-set] :transform (fn [n c] n)}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.extensions.rules/malformed-transform-rule error-type))
      (is (str/includes? message ":scope")))))

(deftest select-shapes-all-load-test
  (testing "every one of haselnuss.select's own four selector shapes
            passes load-time validation: a keyword, a map, a vector, and
            a sandboxed function value (fn? is true of a sci function,
            not only a host one)"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "shapes.clj"
                                 (str "[{:name \"kw\" :select :section :transform (fn [n c] n)}\n"
                                      " {:name \"map\" :select {:t :section} :transform (fn [n c] n)}\n"
                                      " {:name \"vec\" :select [:section :para] :transform (fn [n c] n)}\n"
                                      " {:name \"fn\" :select (fn [n] true) :transform (fn [n c] n)}]"))
          rules (rules/load-transforms [path])]
      (is (= #{"kw" "map" "vec" "fn"} (set (map :name rules)))))))

(deftest ac5-file-read-refused-test
  (testing "AC #5: slurp is not a bindable symbol in this sandbox at all
            -- refused at LOAD time, naming the file -- mirrors
            haselnuss.extensions.sidecar-test's own ac4-file-read-
            refused-test against the identical, shared sandbox"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "readfile.clj"
                                 "{:select :section :transform (fn [n c] (slurp \"/etc/hostname\"))}")
          [status error-type message] (outcome #(rules/load-transforms [path]))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "slurp")))))

(deftest ac5-classpath-require-refused-test
  (testing "AC #5: requiring a real namespace on this project's own
            classpath (haselnuss.parser) from inside a :transform
            function is refused, at CALL time -- mirrors haselnuss.
            extensions.sidecar-test's own ac6-classpath-require-refused-
            test"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "reqclasspath.clj"
                                 (str "{:select :section :transform (fn [n c] "
                                      "(require (symbol \"haselnuss.parser\")) n)}"))
          [rule] (rules/load-transforms [path])
          [status error-type message] (outcome #((:transform rule) minimal-node {}))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/evaluation-error error-type))
      (is (str/includes? message "haselnuss.parser"))))
  (testing "AC #5, positive control: a namespace sci bundles itself
            (clojure.set) is NOT reached via this project's classpath at
            all, so it stays usable"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "reqset.clj"
                                 (str "{:select :section :transform (fn [n c] "
                                      "(require 'clojure.set) (clojure.set/union #{1} #{2}))}"))
          [rule] (rules/load-transforms [path])]
      (is (= #{1 2} ((:transform rule) minimal-node {}))))))

(deftest ac6-non-terminating-transform-stopped-test
  (testing "AC #6: a :transform function that does not terminate is
            bounded by the same sandbox timeout an --extensions renderer
            gets, not merely at load time but at CALL time (wrap-fn) --
            mirrors haselnuss.extensions.sidecar-test's own AC #7"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "infloop.clj"
                                 "{:select :section :transform (fn [n c] (loop [] (recur)))}")
          [rule] (rules/load-transforms [path] {:timeout-ms 200})
          start (System/currentTimeMillis)
          [status error-type message] (outcome #((:transform rule) minimal-node {}))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/timeout error-type))
      (is (str/includes? message "200ms"))
      (is (< elapsed 2000)))))

(deftest ac6-oversized-transform-output-refused-test
  (testing "AC #6: a :transform's own returned value, over a small
            test-local :output-limit, is refused"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "bigout.clj"
                                 (str "{:select :section "
                                      ":transform (fn [n c] (assoc n :extra (apply str (repeat 1000 \"x\"))))}"))
          [rule] (rules/load-transforms [path] {:output-limit 100})
          [status error-type message] (outcome #((:transform rule) minimal-node {}))]
      (is (= :threw status))
      (is (= :haselnuss.sandbox/oversized-output error-type))
      (is (str/includes? message "100-character cap")))))

(deftest ac2-isolation-across-loads-test
  (testing "AC #2 (mirrored from --extensions): two SEPARATE
            load-transforms calls against the same file do not share
            state -- a def'd atom starts fresh every call"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "stateful.clj"
                                 (str "(def counter (atom 0))\n"
                                      "{:select :section :transform (fn [n c] (swap! counter inc))}"))
          [rule-a] (rules/load-transforms [path])
          a1 ((:transform rule-a) minimal-node {})
          a2 ((:transform rule-a) minimal-node {})
          [rule-b] (rules/load-transforms [path])
          b1 ((:transform rule-b) minimal-node {})]
      (is (= [1 2] [a1 a2]))
      (is (= 1 b1)))))

(deftest ac10-no-trust-flag-test
  (testing "AC #10: haselnuss.ext/raw is reachable from inside a
            --transform file's own sandbox (the same shared haselnuss.ext
            namespace every sidecar file sees), and load-transforms
            accepts no :trusted?/:trusted option at all to elevate
            it -- normalize-rule never sets any such key on the rule it
            returns"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "raw.clj"
                                 (str "{:select :section "
                                      ":transform (fn [n c] (haselnuss.ext/raw \"x\"))}"))
          [rule] (rules/load-transforms [path])]
      (is (= [:raw "x"] ((:transform rule) minimal-node {})))
      (is (not (contains? rule :trusted?)))
      (is (not (contains? rule :trusted))))))

(deftest ctx-sharing-test
  (testing "a caller-supplied :ctx (a delay of an already-forked sci
            context) is used instead of forking a fresh one -- two
            load-transforms calls sharing the SAME :ctx see each other's
            top-level def's, unlike two separate calls each forking its
            own (ac2-isolation-across-loads-test, above)"
    (let [dir (scratch-dir!)
          def-path (write-rule-file! dir "def.clj"
                                     "(def shared (atom 41))\n{:select :section :transform (fn [n c] n)}")
          use-path (write-rule-file! dir "use.clj"
                                     "{:select :section :transform (fn [n c] (swap! shared inc))}")
          ctx (delay (sandbox/fork))]
      (rules/load-transforms [def-path] {:ctx ctx})
      (let [[rule] (rules/load-transforms [use-path] {:ctx ctx})]
        (is (= 42 ((:transform rule) minimal-node {})))))))

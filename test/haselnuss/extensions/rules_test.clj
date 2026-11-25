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
            [haselnuss.ast :as ast]
            [haselnuss.extensions.rules :as rules]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.transform :as transform])
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

(deftest task309-positions-survive-sandboxed-transform-test
  (let [src (fn [n line] (ast/with-source n "a.hdoc" line 1))
        para (fn [s line] (src {:t :para :inlines [{:t :str :text s}]} line))
        section (src {:t :section :level 1 :heading [{:t :str :text "H"}]
                      :blocks [(para "one" 3) (para "two" 5)]
                      :attr {:classes [] :props {}}}
                     1)
        load-rule (fn [code]
                    (let [dir (scratch-dir!)
                          path (write-rule-file! dir "r.clj"
                                                 (str "{:select :section :transform " code "}"))]
                      (:transform (first (rules/load-transforms [path])))))
        lines (fn [n] (mapv (comp :line ast/source) (:blocks n)))]
    (testing "a no-op transform returns the node with every position"
      (let [out ((load-rule "(fn [n _] n)") section {})]
        (is (= section out))
        (is (= 1 (:line (ast/source out))))
        (is (= [3 5] (lines out)))))
    (testing "a rewrite keeps the node's and the untouched children's positions; new nodes have none"
      (let [out ((load-rule "(fn [n _] (-> n (assoc :level 2) (update :blocks #(into [{:t :para :inlines []}] %))))")
                 section {})]
        (is (= 2 (:level out)))
        (is (= 1 (:line (ast/source out))))
        (is (= [nil 3 5] (lines out)))))
    (testing "a sequence: the one element equal to the input is the input, the new one has no position"
      (let [[new-para out] ((load-rule "(fn [n _] [{:t :para :inlines []} n])") section {})]
        (is (nil? (ast/source new-para)))
        (is (= 1 (:line (ast/source out))))
        (is (= [3 5] (lines out)))))
    (testing "a script cannot forge a position with its own metadata"
      (let [out ((load-rule "(fn [n _] (assoc n :blocks [(with-meta {:t :para :inlines []} {:haselnuss.ast/source {:line 99}})]))")
                 section {})]
        (is (= [nil] (lines out)))))))

(defn- para-at
  "A paragraph of one word `s`, parsed at `line` of a.hdoc (nil: no position)."
  [s line]
  (cond-> {:t :para :inlines [{:t :str :text s}]}
    line (ast/with-source "a.hdoc" line 1)))

(defn- positions-fixture
  "[section run] for a section of paragraphs `texts` at lines 3, 5, 7...:
  `(run code)` is `[texts lines]` of the blocks the sandboxed `:transform`
  `code` makes of it."
  [texts]
  (let [blocks (vec (map-indexed (fn [i s] (para-at s (+ 3 (* 2 i)))) texts))
        section (-> {:t :section :level 1 :heading [] :attr {:classes [] :props {}} :blocks blocks}
                    (ast/with-source "a.hdoc" 1 1))
        load-rule (fn [code]
                    (let [path (write-rule-file! (scratch-dir!) "r.clj"
                                                 (str "{:select :section :transform " code "}"))]
                      (:transform (first (rules/load-transforms [path])))))]
    [section (fn [code]
               (let [out ((load-rule code) section {})]
                 [(mapv #(-> % :inlines first :text) (:blocks out))
                  (mapv (comp :line ast/source) (:blocks out))]))]))

(deftest task309-reordered-blocks-keep-their-own-positions-test
  (let [[_ run] (positions-fixture ["aa" "bb" "cc" "dd" "ee"])]
    (testing "reversed: every block keeps its own line, none its neighbour's"
      (is (= [["ee" "dd" "cc" "bb" "aa"] [11 9 7 5 3]]
             (run "(fn [n _] (update n :blocks #(vec (reverse %))))"))))
    (testing "the last block moved first"
      (is (= [["ee" "aa" "bb" "cc" "dd"] [11 3 5 7 9]]
             (run "(fn [n _] (update n :blocks #(into [(peek %)] (pop %))))"))))
    (testing "sorted descending"
      (is (= [["ee" "dd" "cc" "bb" "aa"] [11 9 7 5 3]]
             (run "(fn [n _] (update n :blocks #(vec (sort-by (comp :text first :inlines) (fn [a b] (compare b a)) %))))"))))))

(deftest task309-ambiguous-matches-get-no-position-test
  (testing "a new block does not take the position of an old one that is still used"
    (let [[_ run] (positions-fixture ["alpha" "beta"])]
      (is (= [["beta" "NEW"] [5 nil]]
             (run "(fn [n _] (assoc n :blocks [(second (:blocks n)) {:t :para :inlines [{:t :str :text \"NEW\"}]}]))")))))
  (testing "dropping the first of two equal blocks leaves the survivor its own line"
    (let [[_ run] (positions-fixture ["alpha" "beta" "alpha"])]
      (is (= [["beta" "alpha"] [5 7]] (run "(fn [n _] (update n :blocks #(vec (rest %))))")))))
  (testing "which of two equal blocks survived is a guess: no position"
    (let [[_ run] (positions-fixture ["x" "alpha" "alpha"])]
      (is (= [["alpha"] [nil]] (run "(fn [n _] (assoc n :blocks [(last (:blocks n))]))"))))
    (let [[_ run] (positions-fixture ["a" "a" "b"])]
      (is (= [["a" "b"] [nil 7]] (run "(fn [n _] (update n :blocks #(vec (rest %))))")))))
  (testing "equal blocks that all survive, shifted, keep their lines in order"
    (let [[_ run] (positions-fixture ["a" "a" "b"])]
      (is (= [[nil "a" "a" "b"] [nil 3 5 7]]
             (run "(fn [n _] (update n :blocks #(into [{:t :para :inlines []}] %)))")))))
  (testing "a rewritten block has no position, even at its old index"
    (let [[_ run] (positions-fixture ["a" "b" "c"])]
      (is (= [["a" "B" "c"] [3 nil 7]]
             (run "(fn [n _] (assoc-in n [:blocks 1 :inlines 0 :text] \"B\"))"))))))

(deftest task309-match-vector-fuzz-test
  (testing "seeded fuzz (seed 309) of the vector matching: an element only ever
            gets an old element equal to it, no old element twice, and every
            element whose value is unique in both vectors keeps its own"
    (let [match-vector @#'rules/match-vector
          rnd (java.util.Random. 309)
          words ["a" "b" "c" "d" "e" "f" "g" "h"]
          pick (fn [v] (nth v (.nextInt rnd (count v))))
          edit (fn [ws]
                 (case (.nextInt rnd 7)
                   0 (let [l (java.util.ArrayList. ^java.util.Collection ws)]
                       (java.util.Collections/shuffle l rnd)
                       (vec l))
                   1 (if (seq ws) (let [i (.nextInt rnd (count ws))] (into (subvec ws 0 i) (subvec ws (inc i)))) ws)
                   2 (let [i (.nextInt rnd (inc (count ws)))] (into (conj (subvec ws 0 i) (pick words)) (subvec ws i)))
                   3 (if (seq ws) (assoc ws (.nextInt rnd (count ws)) (str "new" (.nextInt rnd 3))) ws)
                   4 (vec (reverse ws))
                   5 (if (seq ws) (conj ws (pick ws)) ws)
                   6 ws))
          stats (volatile! {:cases 0 :kept 0 :moved 0})]
      (dotimes [_ 3000]
        (let [old-words (vec (repeatedly (.nextInt rnd 12) #(pick words)))
              old-v (vec (map-indexed (fn [i w] (para-at w (inc i))) old-words))
              new-words (nth (iterate edit old-words) (inc (.nextInt rnd 3)))
              new-v (mapv #(para-at % nil) new-words)
              out (match-vector old-v new-v)
              kept (filter ast/source out)
              freq-old (frequencies old-words)
              freq-new (frequencies new-words)]
          (vswap! stats update :cases inc)
          (vswap! stats update :kept + (count kept))
          (vswap! stats update :moved + (count (filter (fn [[j x]] (some-> (ast/source x) :line (not= (inc j))))
                                                       (map-indexed vector out))))
          (is (= new-v out) "the content is the rule's")
          (is (every? (fn [x] (some #(identical? x %) old-v)) kept) "a position only from an old element")
          (let [lines (map #(:line (ast/source %)) kept)]
            (is (= (count lines) (count (set lines))) "no old element twice"))
          (doseq [[j w] (map-indexed vector new-words)
                  :when (= 1 (freq-old w 0) (freq-new w))]
            (is (= (inc (.indexOf ^java.util.List old-words w)) (:line (ast/source (nth out j))))
                "a value unique in both keeps its own position"))
          (when (= old-words new-words)
            (is (every? true? (map identical? old-v out)) "unchanged: everything kept"))))
      (is (= 3000 (:cases @stats)))
      (is (< 5000 (:kept @stats)) (str "not vacuous: " @stats))
      (is (< 1000 (:moved @stats)) (str "moved elements matched: " @stats)))))

(defn- colliding-words
  "`n` distinct words of one `String.hashCode` (and so one Clojure hash):
  strings of \"Aa\" and \"BB\" pairs, which hash alike."
  [n]
  (let [k (long (Math/ceil (/ (Math/log n) (Math/log 2))))]
    (map (fn [i] (apply str (map #(if (bit-test i %) "BB" "Aa") (range k)))) (range n))))

(deftest ^:timing task309-reattach-work-is-bounded-test
  (testing "reattachment runs after the sandboxed call, outside its timeout,
            so it must stay linear on shapes chosen to defeat it"
    (testing "20000 paragraphs, 3000 of them returned reversed with their inlines rewritten"
      (let [[_ run] (positions-fixture (map #(str "w" %) (range 20000)))
            start (System/nanoTime)
            [texts lines] (run "(fn [n _] (update n :blocks #(mapv (fn [b] (assoc b :inlines [{:t :str :text \"x\"}])) (take 3000 (reverse %)))))")]
        (is (< (/ (- (System/nanoTime) start) 1e6) 5000))
        (is (= (repeat 3000 "x") texts))
        (is (= (repeat 3000 nil) lines) "rewritten: no positions")))
    (testing "20000 paragraphs whose words all collide in hash, the first 1000 (or those reversed) returned"
      (let [words (colliding-words 20000)
            line-of (zipmap words (iterate #(+ 2 %) 3))
            [_ run] (positions-fixture words)]
        (is (= 20000 (count (distinct words))))
        (is (= 1 (count (distinct (map hash words)))) "the shape is what it claims")
        (doseq [code ["(fn [n _] (update n :blocks #(vec (take 1000 %))))"
                      "(fn [n _] (update n :blocks #(vec (reverse (take 1000 %)))))"]]
          (let [start (System/nanoTime)
                [texts lines] (run code)]
            (is (< (/ (- (System/nanoTime) start) 1e6) 5000) code)
            (is (= 1000 (count texts)))
            (is (every? true? (map (fn [t l] (or (nil? l) (= l (line-of t)))) texts lines))
                "any position kept is the block's own")))))))

(deftest ^:timing ac6-non-terminating-transform-stopped-test
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
      (is (< elapsed 2000))
      ;; TASK-147: the abandoned worker must not keep spinning (Thread.stop
      ;; is unsupported from JDK 20, where it is a documented residual).
      (when (< (.feature (Runtime/version)) 20)
        (let [alive? (fn []
                       (some #(and (str/starts-with? (.getName ^Thread %)
                                                     "haselnuss-sandbox-worker: ")
                                   (.isAlive ^Thread %))
                             (keys (Thread/getAllStackTraces))))]
          (loop [n 0]
            (when (and (alive?) (< n 50))
              (Thread/sleep 100)
              (recur (inc n))))
          (is (not (alive?))))))))

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

;; --- TASK-214: a rule's :select runs in the sandbox --------------------------

(def ^:private doc-of-paras
  "A Document of two paragraphs, `alpha` and `beta`."
  {:meta {}
   :blocks [{:t :para :attr {:classes [] :props {}} :inlines [{:t :str :text "alpha"}]}
            {:t :para :attr {:classes [] :props {}} :inlines [{:t :str :text "beta"}]}]})

(defn- run-rules
  "Loads `source` as a --transform file and runs it over `document`,
  returning `[:ok result]` or `[:threw type message data]`, giving up after
  30 s (`:hung`) so a regression fails instead of hanging the suite."
  ([source document] (run-rules source document {}))
  ([source document opts]
   (let [path (write-rule-file! (scratch-dir!) "rule.clj" source)
         rules (rules/load-transforms [path] opts)
         f (future (try [:ok (transform/transform-document document rules)]
                        (catch Exception e [:threw (:type (ex-data e)) (ex-message e) e])))
         result (deref f 30000 :hung)]
     (when (= :hung result) (future-cancel f))
     result)))

(defn- matched-texts
  "The text of every paragraph `source`'s rule (which marks what it
  matches with an `x` class) matched in `doc-of-paras`."
  [source]
  (let [[status {:keys [document]} :as result] (run-rules source doc-of-paras)]
    (is (= :ok status) (pr-str result))
    (vec (for [b (:blocks document) :when (some #{"x"} (get-in b [:attr :classes]))]
           (get-in b [:inlines 0 :text])))))

(def ^:private mark-x
  ":transform (fn [n c] (update-in n [:attr :classes] conj \"x\"))")

(deftest select-fn-runs-in-the-sandbox-test
  (testing "a :select function's answer decides the match, coerced to a
            boolean, as before"
    (is (= ["alpha"] (matched-texts (str "{:select (fn [n] (and (= :para (:t n)) "
                                         "(some #(= \"alpha\" (:text %)) (:inlines n)))) " mark-x "}"))))
    (is (= ["alpha" "beta"] (matched-texts (str "{:select (fn [n] (when (= :para (:t n)) 1)) " mark-x "}")))))
  (testing "it runs with the script's sci context: building an ex-info or a
            reify inside it works"
    (is (= ["alpha" "beta"]
           (matched-texts (str "{:select (fn [n] (and (= :para (:t n)) (some? (ex-info \"x\" {}))"
                               " (some? (reify Object)))) " mark-x "}")))))
  (testing "a looping :select fails with the sandbox timeout, naming the rule"
    (let [start (System/nanoTime)
          [status error-type message e] (run-rules (str "{:name \"spin\" :select (fn [n] (loop [] (recur))) "
                                                        mark-x "}")
                                                   doc-of-paras {:timeout-ms 200})
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (is (= :threw status))
      (is (= ::sandbox/timeout error-type))
      (is (str/starts-with? message "spin: spin ("))
      (is (str/includes? message "'s :select did not finish within 200ms"))
      (is (= "spin" (:rule (ex-data e))))
      (is (< elapsed-ms 5000) (str "took " elapsed-ms "ms"))))
  (testing "a nested selector function (a map value) runs in the sandbox too"
    (let [[status error-type] (run-rules (str "{:select {:t :para :text (fn [s] (loop [] (recur)))} "
                                              mark-x "}")
                                         doc-of-paras {:timeout-ms 200})]
      (is (= [:threw ::sandbox/timeout] [status error-type])))))

(deftest select-error-is-sanitised-test
  (testing "a :select that throws an ex-info whose data holds a looping
            reify fails as a sandbox evaluation error naming the rule, and
            nothing in the error's cause chain holds script data: printing
            all of it finishes"
    (let [[status error-type message e]
          (run-rules (str "{:name \"thrower\" :select (fn [n] (throw (ex-info \"select boom\" "
                          "{:text (reify Object (toString [_] (loop [] (recur)))) :line 7}"
                          " (ex-info \"inner\" {:x (reify clojure.lang.IDeref (deref [_] (loop [] (recur))))}))))"
                          " " mark-x "}")
                     doc-of-paras)
          chain (take-while some? (iterate ex-cause e))
          printed (future (mapv (comp pr-str ex-data) chain))]
      (is (= :threw status))
      (is (= ::sandbox/evaluation-error error-type))
      (is (str/includes? message "thrower"))
      (is (str/includes? message "select boom"))
      (is (not= :hung (deref printed 10000 :hung)) "printing the cause chain's ex-data hung")
      (is (every? (fn [c] (every? (fn [[k v]] (and (keyword? k)
                                                   (or (nil? v) (string? v) (number? v) (keyword? v)
                                                       (boolean? v))))
                                  (ex-data c)))
                  chain))
      (testing "the script's message and scalar data survive, its reify does not"
        (let [script-error (some #(when (= "select boom" (ex-message %)) %) chain)]
          (is (some? script-error))
          (is (= {:line 7} (ex-data script-error)))
          (is (= "inner" (ex-message (ex-cause script-error))))
          (is (= {} (ex-data (ex-cause script-error)))))))))

(deftest select-regex-is-bounded-test
  (testing "a catastrophically backtracking :select regex times out naming
            the rule, rather than hanging the build"
    (let [doc {:meta {} :blocks [{:t :para :attr {:classes [] :props {}}
                                  :inlines [{:t :str :text (apply str (repeat 50 "a"))}]}]}
          start (System/nanoTime)
          [status error-type message] (run-rules (str "{:name \"redos\" :select {:text #\"(.*a){12}b\"} "
                                                      mark-x "}")
                                                 doc {:timeout-ms 300})
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (is (= :threw status))
      (is (= ::sandbox/timeout error-type))
      (is (str/includes? (str message) "redos"))
      (is (< elapsed-ms 5000) (str "took " elapsed-ms "ms"))))
  (testing "an ordinary regex selector still matches as before"
    (is (= ["beta"] (matched-texts (str "{:select {:t :para :text #\"^b\"} " mark-x "}"))))
    (is (= ["alpha" "beta"] (matched-texts (str "{:select [{:t :para :text #\"^a\"} {:t :para :text \"beta\"}] "
                                                mark-x "}"))))))

(deftest select-matcher-shape-test
  (testing "a plain-data selector gets no sandboxed :matcher; one holding a
            function or regex anywhere does; a :matcher the script supplied
            itself is dropped"
    (let [dir (scratch-dir!)
          path (write-rule-file! dir "shapes.clj"
                                 (str "[{:select :para :transform (fn [n c] n)}\n"
                                      " {:select {:t :para :class #{\"a\"}} :transform (fn [n c] n)}\n"
                                      " {:select :para :matcher (fn [n] (loop [] (recur))) :transform (fn [n c] n)}\n"
                                      " {:select (fn [n] true) :transform (fn [n c] n)}\n"
                                      " {:select {:t :para :id #\"^x\"} :transform (fn [n c] n)}\n"
                                      " {:select [:para {:level (fn [l] true)}] :transform (fn [n c] n)}]"))
          rules (rules/load-transforms [path])]
      (is (= [false false false true true true] (mapv #(contains? % :matcher) rules)))
      (testing "the selector itself is kept as written, naming the rule"
        (is (= :para (:select (nth rules 2))))
        (is (fn? (:select (nth rules 3)))))))
  (testing "the split matcher agrees with matching the whole selector: a map's
            plain part is an AND with its script part, a vector's an OR"
    (is (= [] (matched-texts (str "{:select {:t :section :text #\"a\"} " mark-x "}"))))
    (is (= ["alpha"] (matched-texts (str "{:select {:t :para :text #\"lph\"} " mark-x "}"))))
    (is (= ["alpha" "beta"] (matched-texts (str "{:select [:para :str (fn [n] (loop [] (recur)))] " mark-x "}")))
        "a vector's script element is not run once a plain one matched"))
  (testing "a vector keeps select/matches?'s left-to-right short-circuit: an
            element after a script one that matched is never reached, and a
            script element before a plain one runs first"
    (is (= ["alpha" "beta"] (matched-texts (str "{:select [(fn [_] true) {:prop 3}] " mark-x "}")))
        "the malformed {:prop 3} is not reached")
    (let [[status error-type message]
          (run-rules (str "{:select [(fn [n] (throw (ex-info \"first\" {}))) :para] " mark-x "}")
                     doc-of-paras)]
      (is (= [:threw ::sandbox/evaluation-error] [status error-type]))
      (is (str/includes? (str message) "first")))))

(deftest select-var-refused-test
  (testing "a sci var anywhere in a :select -- whose printing reads metadata
            the script controls -- is refused at load, naming the rule and
            the file, without printing it"
    (doseq [select ["#'sel" "[:para #'sel]" "{:t :para :text #'sel}"]]
      (let [path (write-rule-file! (scratch-dir!) "var.clj"
                                   (str "(defn sel [n] true)\n"
                                        "(alter-meta! #'sel assoc :name (reify Object (toString [_] (loop [] (recur)))))\n"
                                        "{:name \"by-var\" :select " select " :transform (fn [n c] n)}"))
            f (future (outcome #(rules/load-transforms [path])))
            [status error-type message :as result] (deref f 30000 :hung)]
        (is (not= :hung result) select)
        (is (= :threw status) select)
        (is (= ::rules/malformed-transform-rule error-type) select)
        (is (str/includes? (str message) "by-var's :select holds a var") select)
        (is (str/includes? (str message) "var.clj") select)))))

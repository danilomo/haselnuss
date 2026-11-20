(ns haselnuss.library.state-test
  "The saved build state (TASK-265; doc-12, \"What gets rebuilt\" steps 1
  and 7): a round trip gives the graph that was saved, diagnostics
  included; every kind of bad state is 'no usable state', never an
  exception; every file is written through a temporary file and a
  rename, so an interrupted save keeps the old state; and a seeded fuzz
  (seed 265) of `plain` over awkward values checks that what it keeps
  reads back equal through the real write and read."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.memory-index :as memory-index]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.state :as state]
            [haselnuss.version :as version])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Random)))

;; --- helpers -------------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-state" (make-array FileAttribute 0))))

(def ^:private fixture-graph
  "The committed fixture libraries scanned as `--check` scans them: books
  with dangling references, failed scripts, a library that fails to load
  (its diagnostic carries the load problem under `:cause`)."
  (delay (scan/scan (scan/context {:directory-opts {:path "test/fixtures/libraries/libraries.yaml"}})
                    nil)))

(defn- state-files [dir]
  (sort (map #(.getName ^File %) (.listFiles (state/state-dir dir)))))

(defn- header-file ^File [dir]
  (io/file (state/state-dir dir) state/header-name))

(defn- rewrite-header! [dir f]
  (let [h (edn/read-string (slurp (header-file dir)))]
    (spit (header-file dir) (pr-str (f h)))))

(defn- part-files [dir]
  (filter #(re-matches #".*\.[0-9]+\.edn" %) (state-files dir)))

(defn- internal [g]
  (memory-index/internal-state (libraries/backend-graph g)))

(def ^:private book-id {:library "check" :shelf [] :slug "links"})

;; --- AC #1: round trip -----------------------------------------------------------

(deftest round-trip-test
  (let [g @fixture-graph
        data (state/graph-data g)
        dir (temp-dir)
        books {book-id {:output "books/check/links/index.html" :document "abc" :labels {"sec:a" "h1"}}}
        files {"/x/links.hdoc" {:size 12 :mtime 34 :digest "d"}
               "/not/claimed" {:size 1 :mtime 2 :digest "e"}}
        inputs {"/x/libraries.yaml" "digest"}]
    (testing "the fixture graph is not vacuous: it has diagnostics, nested causes and edges"
      (is (< 10 (count (mapcat :diagnostics (vals (:books data))))))
      (is (< 3 (count (mapcat :diagnostics (vals (:libraries data))))))
      (is (some :cause (mapcat :diagnostics (vals (:libraries data)))))
      (is (< 20 (count (mapcat :edges (vals (:books data))))))
      (is (some :label-spans (vals (:books data)))))
    (state/save-state! dir {:graph g :books books :files files :inputs inputs})
    (let [loaded (state/load-state dir {:inputs inputs})]
      (is (:usable? loaded))
      (testing "the loaded graph equals the saved one, diagnostics included"
        (is (= data (state/graph-data (:graph loaded))))
        (is (= (internal g) (internal (:graph loaded)))
            "and so does the memory index behind it, :targets and :files included"))
      (testing "the build's own data comes back as it was"
        (is (= books (:books loaded)))
        (is (= files (:files loaded)))
        (is (= inputs (:inputs loaded)))
        (is (= {} (:listing loaded)) "no listing cache saved, none loaded")
        (is (= (version/version) (:haselnuss loaded)))
        (is (= state/format-version (:format loaded))))
      (testing "the graph answers reads like the saved one"
        (is (= (set (graph/edges-to g book-id)) (set (graph/edges-to (:graph loaded) book-id))))
        (is (= (graph/indexed-libraries g) (graph/indexed-libraries (:graph loaded))))))))

(deftest round-trip-strips-non-data-test
  (let [cause (ex-info "boom" {:x 1})
        diag {:type :odd :message "odd" :level :warning
              :cause cause                       ; an exception: dropped
              :file (io/file "/tmp/x")           ; not plain data: dropped
              :fn inc                            ; dropped
              :bad (keyword "a b")               ; does not read back: dropped
              :nested {:inner {:type :inner :message "in" :ex cause} :ok [1 2.5 "s" \c :k 'sym]}
              :lazy (map inc [1 2 3])}
        record {:id book-id :hash "h" :files #{"/a"} :labels {} :edges []
                :diagnostics [diag]}
        g (graph/with-book (libraries/library-indexed (memory-index/memory-index)) record)
        dir (temp-dir)]
    (state/save-state! dir {:graph g})
    (let [loaded (state/load-state dir)
          [d] (:diagnostics (get (graph/indexed-books (:graph loaded)) book-id))]
      (is (:usable? loaded))
      (is (= {:type :odd :message "odd" :level :warning
              :nested {:inner {:type :inner :message "in"} :ok [1 2.5 "s" \c :k 'sym]}
              :lazy [2 3 4]}
             d))
      (is (= (state/graph-data (graph/with-book g (update record :diagnostics state/plain)))
             (state/graph-data (:graph loaded)))))))

(deftest every-library-part-test
  (testing "a library of more than `part-size` books is saved in several parts, and loads whole"
    (let [n (+ 10 (* 2 state/part-size))
          records (for [i (range n)]
                    {:id {:library "big" :shelf [] :slug (str "b-" i)} :hash (str i)
                     :files #{(str "/big/b-" i ".hdoc")} :labels {} :edges [] :diagnostics []})
          g (reduce graph/with-book (libraries/library-indexed (memory-index/memory-index)) records)
          g (graph/with-library g {:name "big" :status :available :diagnostics []})
          dir (temp-dir)
          files (into {} (for [i (range n)] [(str "/big/b-" i ".hdoc") {:size i :mtime i :digest "x"}]))
          ;; TASK-266: the listing cache is split the same way; a library
          ;; with a listing cache and no records gets a part of its own
          listing {"big" {:files (into {} (for [i (range n)]
                                            [(str "/big/b-" i ".hdoc")
                                             {:stamp {:size i :mtime i :key nil}
                                              :fields {:title [{:t :str :text (str "B " i)}]}
                                              :problems []}]))
                          :shelves {"/big/gen" {:library "big" :shelf ["gen"] :items [{:slug "x"}]}}}
                   "other" {:files {} :shelves {}}}]
      (state/save-state! dir {:graph g :files files :listing listing})
      (is (= 4 (count (part-files dir))))
      (is (= {"big" 3 "other" 1} (:parts (edn/read-string (slurp (header-file dir))))))
      (is (every? #(seq (:files (:listing (edn/read-string (slurp (io/file (state/state-dir dir) %))))))
                  (filter #(str/starts-with? % "big.") (part-files dir)))
          "every part of the big library holds a share of its listing cache")
      (let [loaded (state/load-state dir)]
        (is (= (state/graph-data g) (state/graph-data (:graph loaded))))
        (is (= files (:files loaded)))
        (is (= listing (:listing loaded)))))))

;; --- AC #2: no usable state --------------------------------------------------------

(defn- saved-dir []
  (let [dir (temp-dir)]
    (state/save-state! dir {:graph @fixture-graph :inputs {"/in" "d1"}})
    dir))

(defn- reason [dir & [opts]]
  (let [r (state/load-state dir (or opts {}))]
    (is (false? (:usable? r)))
    (is (string? (:message r)))
    (:reason r)))

(deftest ^:timing no-usable-state-test
  (testing "missing"
    (is (= :missing (reason (temp-dir))))
    (is (= :missing (reason (io/file (temp-dir) "does" "not" "exist")))))
  (testing "an unreadable header"
    (doseq [text ["" "{:format" "(((" "#=(java.lang.System/exit 1)" "#foo/bar 1" "[1 2 3]"
                  (str (apply str (repeat 100000 "[")) (apply str (repeat 100000 "]")))]]
      (let [dir (saved-dir)]
        (spit (header-file dir) text)
        (is (= :unreadable (reason dir)) (subs text 0 (min 20 (count text)))))))
  (testing "a header that is a directory"
    (let [dir (temp-dir)]
      (.mkdirs (header-file dir))
      (is (= :missing (reason dir)))))
  (testing "an older (or newer) format"
    (doseq [f [0 1 (inc state/format-version) (str state/format-version) nil]]
      (let [dir (saved-dir)]
        (rewrite-header! dir #(assoc % :format f))
        (is (= :format (reason dir)) (pr-str f)))))
  (testing "another Haselnuss version"
    (let [dir (saved-dir)]
      (rewrite-header! dir #(assoc % :haselnuss "0.0.1"))
      (is (= :version (reason dir))))
    (let [dir (saved-dir)]
      (rewrite-header! dir #(dissoc % :haselnuss))
      (is (= :version (reason dir)))))
  (testing "this build not knowing its own version"
    (let [dir (saved-dir)]
      (with-redefs [version/version (constantly version/unknown)]
        (is (= :version (reason dir))))))
  (testing "changed inputs"
    (let [dir (saved-dir)]
      (is (= :inputs-changed (reason dir {:inputs {"/in" "d2"}})))
      (is (= :inputs-changed (reason dir {:inputs {}})))
      (is (:usable? (state/load-state dir {:inputs {"/in" "d1"}})))
      (is (:usable? (state/load-state dir)) "no :inputs given: not compared")))
  (testing "inputs keyed by role and position (TASK-268): order and flag are compared"
    (let [dir (temp-dir)
          inputs {[:transform 0 "/a.clj"] "da" [:transform 1 "/b.clj"] "db"}]
      (state/save-state! dir {:graph @fixture-graph :inputs inputs})
      (is (= inputs (:inputs (state/load-state dir {:inputs inputs}))))
      (is (= :inputs-changed (reason dir {:inputs {[:transform 0 "/b.clj"] "db" [:transform 1 "/a.clj"] "da"}}))
          "swapped order")
      (is (= :inputs-changed (reason dir {:inputs {[:extensions 0 "/a.clj"] "da" [:transform 1 "/b.clj"] "db"}}))
          "moved to another flag")))
  (testing "a header whose names would leave the state folder"
    (doseq [f [#(assoc % :generation "../../x")
               #(assoc % :parts {"../x" 1})
               #(assoc % :parts {"check" 0})
               #(assoc % :parts {"check" -1})
               #(assoc % :parts {"check" 100001})
               #(assoc % :parts [])
               #(dissoc % :generation)
               #(assoc % :files nil)]]
      (let [dir (saved-dir)]
        (rewrite-header! dir f)
        (is (= :unreadable (reason dir))))))
  (testing "a header claiming very many parts is unreadable at once, before any part is read"
    (doseq [parts [(into {} (for [i (range 5)] [(str "lib-" i) 100000]))
                   ;; few enough to pass the file count, but one is not there
                   {"check" 1 "zzz" 1}]]
      (let [dir (saved-dir)
            reads (atom 0)
            real-read @#'state/read-part]
        (rewrite-header! dir #(assoc % :parts parts))
        (with-redefs [state/read-part (fn [& args] (swap! reads inc) (apply real-read args))]
          (let [t0 (System/nanoTime)]
            (is (= :unreadable (reason dir)))
            (is (< (- (System/nanoTime) t0) 1e9) "well under a second")))
        (is (zero? @reads) (pr-str (keys parts))))))
  (testing "a missing, truncated or invalid library part"
    (let [dir (saved-dir)]
      (.delete (io/file (state/state-dir dir) (first (part-files dir))))
      (is (= :unreadable (reason dir))))
    (let [dir (saved-dir)
          f (io/file (state/state-dir dir) (first (part-files dir)))
          text (slurp f)]
      (spit f (subs text 0 (quot (count text) 2)))
      (is (= :unreadable (reason dir))))
    (let [dir (saved-dir)
          f (io/file (state/state-dir dir) (first (filter #(str/starts-with? % "check.") (part-files dir))))
          part (edn/read-string (slurp f))]
      (spit f (pr-str (assoc-in part [:books 0 :hash] 42)))
      (is (= :unreadable (reason dir)) "a book record that is not one")
      (spit f (pr-str (assoc part :name "other")))
      (is (= :unreadable (reason dir)) "the part of another library")
      (spit f (pr-str (assoc part :books {})))
      (is (= :unreadable (reason dir)))
      (spit f (pr-str part))
      (is (:usable? (state/load-state dir)) "and the part as it was loads again"))))

;; --- AC #4: atomic writes ------------------------------------------------------------

(deftest write-atomically-test
  (let [dir (temp-dir)
        target (io/file dir "x.edn")]
    (spit target "old")
    (testing "the content goes to a temporary file in the target's folder, then renamed"
      (let [during (atom nil)
            target-during (atom nil)]
        (state/write-atomically! target (fn [w]
                                          (.write ^java.io.Writer w "new")
                                          (reset! during (sort (map #(.getName ^File %) (.listFiles dir))))
                                          (reset! target-during (slurp target))))
        (is (= 2 (count @during)))
        (is (some #(re-matches #"\.x\.edn\..*\.tmp" %) @during))
        (is (= "old" @target-during) "untouched while the new content is written")
        (is (= "new" (slurp target)))
        (is (= ["x.edn"] (map #(.getName ^File %) (.listFiles dir))) "no temporary file left")))
    (testing "a failed write leaves the target as it was, and no temporary file"
      (is (thrown? clojure.lang.ExceptionInfo
                   (state/write-atomically! target (fn [w]
                                                     (.write ^java.io.Writer w "partial")
                                                     (throw (ex-info "disk full" {}))))))
      (is (= "new" (slurp target)))
      (is (= ["x.edn"] (map #(.getName ^File %) (.listFiles dir)))))))

(deftest interrupted-save-keeps-old-state-test
  (let [g @fixture-graph
        dir (temp-dir)
        g2 (graph/without-book g book-id)
        real-write state/write-atomically!]
    (state/save-state! dir {:graph g})
    (let [before (state-files dir)]
      (doseq [fail-on [#(str/ends-with? % ".edn")   ; the first part
                       #(= % state/header-name)]]   ; the header, after every part
        (testing "a save that fails part-way leaves the old state loadable"
          (with-redefs [state/write-atomically!
                        (fn [^File target write!]
                          (if (fail-on (.getName target))
                            (throw (ex-info "interrupted" {}))
                            (real-write target write!)))]
            (is (thrown? clojure.lang.ExceptionInfo (state/save-state! dir {:graph g2}))))
          (let [loaded (state/load-state dir)]
            (is (:usable? loaded))
            (is (= (state/graph-data g) (state/graph-data (:graph loaded)))))))
      (testing "the next save replaces it and removes the files of every other generation"
        (state/save-state! dir {:graph g2})
        (is (= (state/graph-data g2) (state/graph-data (:graph (state/load-state dir)))))
        (is (= (count before) (count (state-files dir))))
        (is (empty? (filter #(str/ends-with? % ".tmp") (state-files dir))))
        (is (not-any? (set before) (part-files dir)))))))

(deftest stale-removal-touches-only-own-files-test
  (let [dir (saved-dir)
        sdir (state/state-dir dir)
        stray ["notes.edn" "precious.edn" "foo.tmp" ".x.tmp" "README" "Notes.abc.0.edn"
               "notes.xyz.0.edn" ".state.edn.tmp" "notes.abc.0.edn.bak"]
        stale ["notes.abc123.0.edn" "check.0f.12.edn" ".state.edn.123456.tmp"
               ".notes.abc123.0.edn.987.tmp"]]
    (doseq [n (concat stray stale)] (spit (io/file sdir n) "x"))
    (state/save-state! dir {:graph @fixture-graph})
    (testing "files a save did not write survive"
      (is (every? #(.isFile (io/file sdir %)) stray)))
    (testing "an old generation's parts and leftover temporary files go"
      (is (not-any? #(.exists (io/file sdir %)) stale)))
    (is (:usable? (state/load-state dir)))))

(defn- symlink! [^File link ^File target]
  (io/make-parents link)
  (Files/createSymbolicLink (.toPath link) (.toPath target) (make-array FileAttribute 0)))

(deftest symlinked-state-dir-test
  (doseq [[what link-of] [[".haselnuss/state" #(state/state-dir %)]
                          [".haselnuss" #(.getParentFile (state/state-dir %))]]]
    (testing (str "a " what " that is a symbolic link")
      (let [dir (temp-dir)
            elsewhere (temp-dir)
            precious (io/file elsewhere "precious.edn")
            old-part (io/file elsewhere "notes.abc123.0.edn")]
        (spit precious "keep me")
        (spit old-part "keep me too")
        (symlink! (link-of dir) elsewhere)
        (testing "is refused by save, which writes and deletes nothing"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link"
                                (state/save-state! dir {:graph @fixture-graph})))
          (is (= #{"precious.edn" "notes.abc123.0.edn"}
                 (set (.list elsewhere))))
          (is (= "keep me" (slurp precious)))
          (is (= "keep me too" (slurp old-part))))
        (testing "is no usable state on load"
          (is (= :symlinked (reason dir))))))))

(deftest save-refuses-bad-input-test
  (let [dir (temp-dir)
        g (libraries/library-indexed (memory-index/memory-index))]
    (doseq [bad [{:inputs {:a "b"}} {:inputs {"a" 1}} {:inputs {[:t -1 "a"] "d"}}
                 {:inputs {["t" 0 "a"] "d"}} {:inputs {[:t 0] "d"}} {:books {"x" {}}} {:books {book-id 1}}
                 {:files {"a" 1}} {:files []}
                 {:listing {"Bad Name" {}}} {:listing {"lib" 1}} {:listing {"lib" {:files []}}}
                 {:listing []}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"save-state!"
                            (state/save-state! dir (assoc bad :graph g)))
          (pr-str bad)))
    (is (= :missing (:reason (state/load-state dir))) "and writes nothing")))

;; --- files ---------------------------------------------------------------------------

(deftest file-stamp-test
  (let [dir (temp-dir)
        f (io/file dir "a.txt")]
    (spit f "hello")
    (let [s (state/file-stamp f)]
      (is (= 5 (:size s)))
      (is (pos? (:mtime s)))
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" (:digest s))))
    (is (nil? (state/file-stamp (io/file dir "missing"))))
    (is (nil? (state/file-stamp dir)) "a folder has no stamp")
    (is (= {(str f) "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            (str (io/file dir "missing")) "missing"}
           (state/input-digests [f (io/file dir "missing")])))
    (is (= {[:transform 0 (str f)] "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            [:transform 1 (str (io/file dir "missing"))] "missing"}
           (state/input-digests [[:transform 0 f] [:transform 1 (io/file dir "missing")]])))))

;; --- plain: seeded fuzz ----------------------------------------------------------------

(def ^:private odd-chars
  "Characters the EDN writer and reader treat specially."
  (vec (str "\"\\\n\r\t ,;#:/{}[]()^@~`'"
            "\u0000\u0001\u001c\u001f\u007f  é中")))

(defn- random-string [^Random rnd]
  (apply str (repeatedly (.nextInt rnd 8)
                         #(case (.nextInt rnd 4)
                            0 (nth odd-chars (.nextInt rnd (count odd-chars)))
                            1 "🌰"            ; a supplementary character, as its pair
                            (char (+ 97 (.nextInt rnd 26)))))))

(defn- random-name [^Random rnd]
  (case (.nextInt rnd 4)
    0 (str (char (+ 97 (.nextInt rnd 26))) (.nextInt rnd 100))
    1 (random-string rnd)
    2 (str (.nextInt rnd 10) "x")
    (str "a/b" (random-string rnd))))

(defn- random-value [^Random rnd depth]
  (let [scalar #(case (.nextInt rnd 14)
                  0 nil
                  1 (.nextBoolean rnd)
                  2 (.nextLong rnd)
                  3 (int (.nextInt rnd))
                  4 (* (.nextGaussian rnd) 1e10)
                  5 (float (.nextGaussian rnd))
                  6 (random-string rnd)
                  7 (nth odd-chars (.nextInt rnd (count odd-chars)))
                  8 (keyword (random-name rnd))
                  9 (if (.nextBoolean rnd) (keyword (random-name rnd) (random-name rnd)) (symbol (random-name rnd)))
                  10 (ex-info (random-string rnd) {})
                  11 (io/file (random-string rnd))
                  12 (/ 1 (inc (.nextInt rnd 5)))
                  13 ##Inf)]
    (if (or (zero? depth) (< (.nextInt rnd 10) 4))
      (scalar)
      (case (.nextInt rnd 5)
        0 (into {} (repeatedly (.nextInt rnd 4) #(vector (random-value rnd (dec depth)) (random-value rnd (dec depth)))))
        1 (vec (repeatedly (.nextInt rnd 4) #(random-value rnd (dec depth))))
        2 (set (repeatedly (.nextInt rnd 4) #(random-value rnd (dec depth))))
        3 (map identity (repeatedly (.nextInt rnd 4) #(random-value rnd (dec depth))))
        (list* (repeatedly (.nextInt rnd 3) #(random-value rnd (dec depth))))))))

(defn- leaves [x]
  (cond (map? x) (mapcat leaves (mapcat identity x))
        (coll? x) (mapcat leaves x)
        :else [x]))

(deftest plain-fuzz-test
  (let [rnd (Random. 265)
        dir (temp-dir)
        f (io/file dir "fuzz.edn")
        counts (atom {:cases 0 :dropped 0 :odd-strings 0 :keywords-kept 0 :keywords-dropped 0
                      :odd-chars-kept 0})]
    (dotimes [_ 600]
      (let [x (random-value rnd 4)
            p (state/plain x)
            in (leaves x)
            out (set (leaves p))]
        (#'state/write-edn! f p)
        (let [back (#'state/read-edn f)]
          (is (= p back) (pr-str x))
          (is (= p (state/plain p)) "plain is idempotent"))
        (swap! counts
               (fn [c]
                 (cond-> (update c :cases inc)
                   (not= (count in) (count (leaves p))) (update :dropped inc)
                   (some #(and (string? %) (some (set odd-chars) %)) out) (update :odd-strings inc)
                   (some keyword? out) (update :keywords-kept inc)
                   (some #(and (keyword? %) (not (contains? out %))) in) (update :keywords-dropped inc)
                   (some #(and (char? %) (some #{%} odd-chars)) out) (update :odd-chars-kept inc))))))
    (testing "the fuzz is not vacuous"
      (let [c @counts]
        (is (= 600 (:cases c)))
        (is (< 50 (:dropped c)) (pr-str c))
        (is (< 50 (:odd-strings c)) (pr-str c))
        (is (< 50 (:keywords-kept c)) (pr-str c))
        (is (< 10 (:keywords-dropped c)) (pr-str c))
        (is (< 10 (:odd-chars-kept c)) (pr-str c))))))

(defn- reshaped
  "`x` with some of its maps, vectors and sets made the other kinds of
  each a state can hold or `pr-plain!` must leave to `pr`: sorted maps
  and sets, array maps, map entries, subvectors, records."
  [^Random rnd x]
  (let [sorted (fn [to xs] (try (into to xs) (catch Exception _ nil)))]
    (cond
      (map? x) (let [m (into {} (map (fn [[k v]] [(reshaped rnd k) (reshaped rnd v)])) x)]
                 (case (.nextInt rnd 4)
                   0 (or (sorted (sorted-map) m) m)
                   1 (apply array-map (mapcat identity m))
                   2 (if-let [[k v] (first m)] (clojure.lang.MapEntry/create k v) m)
                   m))
      (vector? x) (let [v (mapv #(reshaped rnd %) x)]
                    (if (and (seq v) (.nextBoolean rnd)) (subvec v (.nextInt rnd (count v))) v))
      (set? x) (let [s (into #{} (map #(reshaped rnd %)) x)]
                 (or (when (.nextBoolean rnd) (sorted (sorted-set) s)) s))
      (seq? x) (map #(reshaped rnd %) x)
      ;; every character `pr` escapes in a string
      (string? x) (if (.nextBoolean rnd) (str x (nth ["\b" "\f" "\"" "\\" "\n" "\t" "\r"] (.nextInt rnd 7)) x) x)
      :else x)))

(defrecord ^:private Odd [a])

(deftest pr-plain-is-pr-test
  (testing "TASK-297: the state's writer writes exactly what pr writes (seed 297)"
    (let [rnd (Random. 297)
          pr-plain! @#'state/pr-plain!
          counts (atom {})
          printed (fn [x]
                    (binding [*print-length* nil *print-level* nil *print-meta* false *print-dup* false
                              *print-readably* true *print-namespace-maps* false]
                      [(pr-str x)
                       (let [w (java.io.StringWriter.)] (pr-plain! w x) (str w))]))
          kinds (fn [x] (frequencies (map (fn [v] (cond (string? v) :string (keyword? v) :keyword
                                                        (instance? Long v) :long (double? v) :double
                                                        (char? v) :char (symbol? v) :symbol
                                                        :else :other))
                                          (leaves x))))]
      (dotimes [_ 3000]
        (let [v (random-value rnd 4)
              ;; a sorted map met as a key can fail to compare with another
              x (try (reshaped rnd v) (catch ClassCastException _ v))
              x (if (zero? (.nextInt rnd 20)) (->Odd x) x)
              [expected actual] (printed x)]
          (is (= expected actual) (pr-str x))
          (swap! counts #(merge-with + % (kinds x)
                                     {:sorted (count (filter sorted? (tree-seq coll? seq x)))
                                      :entries (count (filter map-entry? (tree-seq coll? seq x)))
                                      :records (if (instance? Odd x) 1 0)}))))
      (testing (str "not vacuous: " (pr-str @counts))
        (is (every? #(< 100 (get @counts % 0)) [:string :keyword :long :char :symbol :double :other]))
        (is (< 100 (:sorted @counts)))
        (is (< 50 (:entries @counts)))
        (is (< 50 (:records @counts)))))))

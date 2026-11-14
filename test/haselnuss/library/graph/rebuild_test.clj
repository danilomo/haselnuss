(ns haselnuss.library.graph.rebuild-test
  "Rebuild dependencies (TASK-239): `affected-by` and `affected-by-files`
  over a `:memory-index` graph -- pinned cases for each rule, then a
  seeded generated-graph check against a naive closure computed from
  `edges-from` of every indexed book."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.contract :refer [edge record]]
            [haselnuss.library.graph.memory-index :as memory-index]
            [haselnuss.library.graph.rebuild :as rebuild])
  (:import (java.util Random)))

(defn- id [slug] (book-ref/book-id "lib" ["s"] slug))

(defn- graph-of
  "A `:memory-index` graph holding `records`."
  [& records]
  (reduce graph/with-book (memory-index/memory-index) records))

(defn- book
  "A record for `slug` with `edges`, each `[kind target-slug]` (resolved)
  or a full edge map."
  [slug & edges]
  (let [from (id slug)]
    (record from {:edges (mapv (fn [e]
                                 (if (map? e)
                                   e
                                   (let [[kind to] e] (edge from {:to-id (id to) :kind kind}))))
                               edges)})))

(deftest link-dependents-are-one-hop-test
  ;; a -> b -> c by links: changing c rebuilds b (its link text) but not a.
  (let [g (graph-of (book "a" [:link "b"]) (book "b" [:link "c"]) (book "c"))]
    (is (= #{(id "c") (id "b")} (rebuild/affected-by g [(id "c")])))
    (is (= #{(id "a")} (rebuild/affected-by g #{(id "a")})))
    (is (= #{} (rebuild/affected-by g [])))))

(deftest quote-dependents-are-transitive-test
  (testing "A quotes B quotes C: changing C yields A, B, C"
    (let [g (graph-of (book "a" [:quote "b"]) (book "b" [:quote "c"]) (book "c"))]
      (is (= #{(id "a") (id "b") (id "c")} (rebuild/affected-by g [(id "c")])))
      (is (= #{(id "a") (id "b")} (rebuild/affected-by g [(id "b")])))))
  (testing "a quote cycle terminates"
    (let [g (graph-of (book "x" [:quote "y"]) (book "y" [:quote "z"]) (book "z" [:quote "x"])
                      (book "w" [:link "y"]))]
      (is (= #{(id "x") (id "y") (id "z") (id "w")} (rebuild/affected-by g [(id "x")])))
      (testing "and a self-quote"
        (is (= #{(id "s")} (rebuild/affected-by (graph-of (book "s" [:quote "s"])) [(id "s")]))))))
  (testing "a book linking to a quote dependent is included, one hop past it only"
    ;; l links to a, which quotes c; m links to l.
    (let [g (graph-of (book "a" [:quote "c"]) (book "c") (book "l" [:link "a"]) (book "m" [:link "l"]))]
      (is (= #{(id "c") (id "a") (id "l")} (rebuild/affected-by g [(id "c")]))))))

(deftest dangling-dependents-test
  (let [new-book (id "new")
        ;; written on another shelf than the book's canonical one, no :to :id
        missing-book (edge (id "d") {:written "lib:elsewhere/new#1:1"
                                     :passage {:form :locator :locator "1:1"}
                                     :problem :unknown-book})
        ;; an :unknown-label edge carries the resolved book's :to :id
        missing-label (edge (id "e") {:to-id (id "c") :passage {:form :label :label "sec:gone"}
                                      :problem :unknown-label})
        ;; same label, but :to :id names a stale shelf: still by library + slug
        stale-label (edge (id "f") {:to-id (book-ref/book-id "lib" ["old"] "c")
                                    :passage {:form :label :label "sec:gone"}
                                    :problem :unknown-label})
        other-slug (edge (id "o") {:written "lib:newer" :problem :unknown-book})
        g (graph-of (book "c") (book "d" missing-book) (book "e" missing-label)
                    (book "f" stale-label) (book "o" other-slug))]
    (testing "a dangling edge naming a book that did not exist"
      (is (= #{new-book (id "d")} (rebuild/affected-by g [new-book]))))
    (testing "a dangling edge naming a label the book lacked, with or without the current shelf"
      (is (= #{(id "c") (id "e") (id "f")} (rebuild/affected-by g [(id "c")]))))
    (testing "a dangling edge in another library is not included"
      (is (= #{(book-ref/book-id "other" ["s"] "new")}
             (rebuild/affected-by g [(book-ref/book-id "other" ["s"] "new")]))))))

(deftest dangling-quote-continues-the-closure-test
  ;; q quotes the not-yet-existing book `new`; r quotes q; t links to q.
  ;; Once `new` exists q's transclusion may resolve, so q's output changes
  ;; and r (which transcludes q) follows.
  (let [new-book (id "new")
        g (graph-of (book "q" (edge (id "q") {:written "lib:new#1" :kind :quote :problem :unknown-book}))
                    (book "r" [:quote "q"])
                    (book "t" [:link "q"]))]
    (is (= #{new-book (id "q") (id "r") (id "t")}
           (rebuild/affected-by g [new-book])))))

(deftest quoters-of-link-dependents-test
  ;; A book read for quoting leaves its own @{…} for the quoting host to
  ;; resolve, so a quoter of a link dependent shows the link's current
  ;; text and must be rebuilt -- but no further link hop is taken.
  (testing "Y quotes X, which links to B"
    (let [g (graph-of (book "y" [:quote "x"]) (book "x" [:link "b"]) (book "b")
                      (book "k" [:link "y"]) (book "m" [:link "x"]))]
      (is (= #{(id "b") (id "x") (id "y")} (rebuild/affected-by g [(id "b")])))))
  (testing "Y quotes X, which has a dangling link to a book now created"
    (let [g (graph-of (book "y" [:quote "x"])
                      (book "x" (edge (id "x") {:written "lib:new" :problem :unknown-book})))]
      (is (= #{(id "new") (id "x") (id "y")} (rebuild/affected-by g [(id "new")])))))
  (testing "nested: Y quotes X quotes Z, which links to B"
    (let [g (graph-of (book "y" [:quote "x"]) (book "x" [:quote "z"]) (book "z" [:link "b"])
                      (book "b") (book "w" [:link "y"]))]
      (is (= #{(id "b") (id "z") (id "x") (id "y")} (rebuild/affected-by g [(id "b")]))))))

(deftest ids-not-in-the-graph-test
  (testing "a deleted book is in the answer and its dependents are found"
    (let [g (-> (graph-of (book "a" [:quote "b"]) (book "b" [:quote "c"]) (book "c")
                          (book "l" [:link "c"]))
                (graph/without-book (id "c")))]
      (is (not (contains? (graph/indexed-books g) (id "c"))))
      (is (= #{(id "a") (id "b") (id "c") (id "l")} (rebuild/affected-by g [(id "c")])))))
  (testing "an id nothing refers to is answered alone"
    (is (= #{(id "zz")} (rebuild/affected-by (graph-of (book "a")) [(id "zz")])))))

(deftest affected-by-files-test
  (let [g (graph-of (record (id "a") {:files #{"a.hdoc"} :edges [(edge (id "a") {:to-id (id "b") :kind :quote})]})
                    (record (id "b") {:files #{"b.hdoc" "shared.hdoc"}})
                    (record (id "c") {:files #{"c.hdoc" "shared.hdoc"}})
                    (record (id "l") {:files #{"l.hdoc"} :edges [(edge (id "l") {:to-id (id "c")})]}))]
    (testing "paths map through books-for-file, then affected-by"
      (is (= {:books #{(id "b") (id "a")} :unclaimed #{}}
             (rebuild/affected-by-files g ["b.hdoc"])))
      (is (= {:books #{(id "b") (id "a") (id "c") (id "l")} :unclaimed #{}}
             (rebuild/affected-by-files g #{"shared.hdoc"}))))
    (testing "paths no book claims are reported back"
      (is (= {:books #{(id "l")} :unclaimed #{"new.hdoc" "./l.hdoc"}}
             (rebuild/affected-by-files g ["l.hdoc" "new.hdoc" "./l.hdoc"])))
      (is (= {:books #{} :unclaimed #{"new.hdoc"}}
             (rebuild/affected-by-files g ["new.hdoc"])))
      (is (= {:books #{} :unclaimed #{}} (rebuild/affected-by-files g []))))))

(deftest only-portable-operations-test
  ;; A graph that answers only the six protocol operations (no backend
  ;; internals) is enough; GraphQuery is not implemented.
  (let [inner (graph-of (book "a" [:quote "b"]) (book "b" [:link "c"]) (book "c"))
        portable (reify graph/ReferenceGraph
                   (with-book [_ r] (graph/with-book inner r))
                   (without-book [_ i] (graph/without-book inner i))
                   (indexed-books [_] (graph/indexed-books inner))
                   (edges-from [_ i] (graph/edges-from inner i))
                   (edges-to [_ t] (graph/edges-to inner t))
                   (books-for-file [_ p] (graph/books-for-file inner p)))]
    (is (not (satisfies? graph/GraphQuery portable)))
    (is (= #{(id "a") (id "b")} (rebuild/affected-by portable [(id "b")])))))

;; --- generated graphs against a naive closure --------------------------------

(def ^:private universe
  "Ids the generator draws from: two libraries, slugs shared across
  shelves, a root-level (shelf-less) book, and ids never indexed."
  (vec (for [lib ["p" "q"]
             [shelf slug] [[["a"] "one"] [["b"] "one"] [["a"] "two"] [[] "three"]
                           [["a"] "four"] [["c" "d"] "five"] [["a"] "ghost"]]]
         (book-ref/book-id lib shelf slug))))

(defn- pick [^Random rnd xs] (nth xs (.nextInt rnd (count xs))))

(defn- random-edge [^Random rnd from]
  (let [to (pick rnd universe)
        kind (pick rnd [:link :quote])]
    (case (int (.nextInt rnd 4))
      (0 1) (edge from {:to-id to :kind kind})
      2 (edge from {:to-id to :kind kind :passage {:form :label :label "x"} :problem :unknown-label})
      ;; named by :written only, maybe on another shelf
      3 (edge from {:written (str (:library to) ":" (pick rnd ["" "zz/"]) (:slug to))
                    :kind kind :problem :unknown-book}))))

(defn- random-graph [^Random rnd]
  (reduce (fn [g from]
            (if (or (= "ghost" (:slug from)) (zero? (.nextInt rnd 5)))
              g
              (graph/with-book g (record from {:edges (vec (repeatedly (.nextInt rnd 4)
                                                                       #(random-edge rnd from)))}))))
          (memory-index/memory-index)
          universe))

(defn- into-book?
  "The naive statement of \"an edge into book `b`\": resolved, to `b`
  itself (any same-library-and-slug book when `b` has no shelf); dangling,
  naming `b`'s library and slug by `:to :id` or by what was written."
  [b e]
  (let [same-key? (fn [x] (and x (= [(:library x) (:slug x)] [(:library b) (:slug b)])))
        to (:to e)]
    (if (= :resolved (:status e))
      (and (same-key? (:id to)) (or (empty? (:shelf b)) (= b (:id to))))
      (boolean (same-key? (or (:id to) (:id (book-ref/parse-book-ref (:written to)))))))))

(defn- all-edges [g]
  (mapcat #(graph/edges-from g %) (keys (graph/indexed-books g))))

(defn- sources-into
  "The sources of the `edges` (optionally only those of `kind`) into any
  book in `bs`."
  ([edges bs] (sources-into edges bs nil))
  ([edges bs kind]
   (into #{}
         (comp (filter #(or (nil? kind) (= kind (:kind %))))
               (filter (fn [e] (some #(into-book? % e) bs)))
               (map (comp :id :from)))
         edges)))

(defn- naive-affected-by
  "The rebuild set as a path search, stated independently of the code's
  two-closure structure: Y is affected by an input B exactly when some
  edge path Y -> ... -> B, over every edge of every indexed book, has
  kinds matching `quote* (any)? quote*` (`any`: a link, a quote, or a
  dangling edge). Searched backwards from the inputs over `[book
  used-any?]` states to a fixed point. Answers `:result` plus the pieces
  the non-vacuity counts need."
  [g ids]
  (let [edges (all-edges g)
        step (fn [[b used?]]
               (for [e edges
                     :when (into-book? b e)
                     :let [src (:id (:from e))]
                     state (cond-> []
                             (= :quote (:kind e)) (conj [src used?])
                             (not used?) (conj [src true]))]
                 state))
        states (loop [seen (set (map (fn [b] [b false]) ids))]
                 (let [more (into seen (mapcat step) seen)]
                   (if (= more seen) seen (recur more))))
        changed (into #{} (keep (fn [[b used?]] (when-not used? b))) states)
        one-hop (into #{} (comp (filter #(and (or (= :link (:kind %)) (= :dangling (:status %)))
                                              (some (fn [b] (into-book? b %)) changed)))
                                (map (comp :id :from)))
                      edges)]
    {:quote-1 (into (set ids) (sources-into edges ids :quote))
     :changed changed
     :one-hop (remove changed one-hop)
     :result (into #{} (map first) states)}))

(deftest generated-graphs-match-naive-closure-test
  (let [rnd (Random. 239)
        counts (atom {:cases 0 :mismatches 0 :quote-depth-2 0 :dangling-hits 0
                      :unindexed-with-dependents 0 :link-only-excluded 0
                      :quoter-of-link-dependent 0})]
    (dotimes [_ 200]
      (let [g (random-graph rnd)
            ids (vec (repeatedly (inc (.nextInt rnd 3)) #(pick rnd universe)))
            {:keys [quote-1 changed one-hop result]} (naive-affected-by g ids)
            got (rebuild/affected-by g ids)
            edges (all-edges g)
            link-only (remove changed result)]
        ;; a book reached only by quoting a link (or dangling) dependent
        (when (seq (remove (into changed one-hop) result))
          (swap! counts update :quoter-of-link-dependent inc))
        (is (= result got) (str "ids " (pr-str ids)))
        (swap! counts update :cases inc)
        (when (not= result got) (swap! counts update :mismatches inc))
        ;; a quote dependent reached only through another quote dependent
        (when (seq (remove quote-1 changed))
          (swap! counts update :quote-depth-2 inc))
        (when (some #(and (= :dangling (:status %)) (some (fn [b] (into-book? b %)) got)) edges)
          (swap! counts update :dangling-hits inc))
        (when (some #(and (not (contains? (graph/indexed-books g) %))
                          (seq (graph/edges-to g %)))
                    ids)
          (swap! counts update :unindexed-with-dependents inc))
        ;; a book linking to a link-only dependent, left out
        (when (seq (remove result (sources-into edges link-only :link)))
          (swap! counts update :link-only-excluded inc))))
    (let [c @counts]
      (is (zero? (:mismatches c)))
      (doseq [k [:quote-depth-2 :dangling-hits :unindexed-with-dependents :link-only-excluded
                 :quoter-of-link-dependent]]
        (is (pos? (get c k)) (str "vacuous: no case hit " k " " (pr-str c)))))))

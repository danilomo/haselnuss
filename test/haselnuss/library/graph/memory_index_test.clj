(ns haselnuss.library.graph.memory-index-test
  "The `:memory-index` backend (TASK-238 AC #4): it passes the shared
  `ReferenceGraph` contract (`haselnuss.library.graph.contract`), and the
  contract is not vacuous -- backends broken in each of the ways it is
  meant to catch fail it."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.contract :as contract]
            [haselnuss.library.graph.memory-index :as memory-index]))

(deftest graph-contract-test
  (let [counts (contract/check-graph-contract {:new-graph memory-index/memory-index})]
    (is (zero? (:mismatches counts)))))

;; --- broken backends ---------------------------------------------------------

(defn- wrap
  "A backend over the memory index whose writes pass through `on-write`
  (`(fn [g record] record')`) and whose `books-for-file` through
  `on-files`."
  [g {:keys [on-write on-files] :or {on-write (fn [_ r] r) on-files identity}}]
  (reify graph/ReferenceGraph
    (with-book [_ r] (wrap (graph/with-book g (on-write g r)) {:on-write on-write :on-files on-files}))
    (without-book [_ id] (wrap (graph/without-book g id) {:on-write on-write :on-files on-files}))
    (indexed-books [_] (graph/indexed-books g))
    (edges-from [_ id] (graph/edges-from g id))
    (edges-to [_ target] (graph/edges-to g target))
    (books-for-file [_ path] (on-files (graph/books-for-file g path)))))

(defn- mutable-backend
  "A graph that mutates itself in place: every write returns the same
  object, so old values see new writes."
  []
  (let [state (atom (memory-index/memory-index))]
    (reify graph/ReferenceGraph
      (with-book [this r] (swap! state graph/with-book r) this)
      (without-book [this id] (swap! state graph/without-book id) this)
      (indexed-books [_] (graph/indexed-books @state))
      (edges-from [_ id] (graph/edges-from @state id))
      (edges-to [_ target] (graph/edges-to @state target))
      (books-for-file [_ path] (graph/books-for-file @state path)))))

(defn- contract-failures
  "The `:fail`/`:error` reports the contract makes against `new-graph`,
  captured instead of counted against this test."
  [new-graph]
  (let [reports (atom [])]
    (binding [t/report (fn [m] (when (#{:fail :error} (:type m)) (swap! reports conj m)))]
      (contract/check-graph-contract {:new-graph new-graph
                                      :random {:histories 10 :steps 10}}))
    @reports))

(deftest contract-rejects-broken-backends-test
  (testing "a backend that mutates in place fails (snapshot reads)"
    (is (seq (contract-failures mutable-backend))))
  (testing "a backend that merges a rescan into the old edges fails (replace entirely)"
    (is (seq (contract-failures
              #(wrap (memory-index/memory-index)
                     {:on-write (fn [g r]
                                  (update r :edges (fn [es] (into (vec (graph/edges-from g (:id r))) es))))})))))
  (testing "a backend that drops dangling edges fails"
    (is (seq (contract-failures
              #(wrap (memory-index/memory-index)
                     {:on-write (fn [_ r]
                                  (update r :edges (fn [es] (filterv (comp #{:resolved} :status) es))))})))))
  (testing "a backend that keeps only one claimer per file fails"
    (is (seq (contract-failures
              #(wrap (memory-index/memory-index)
                     {:on-files (fn [ids] (set (take 1 (sort-by str ids))))}))))))

;; --- index shrinkage (TASK-245) ---------------------------------------------------

(def ^:private state
  "The plain map behind a memory-index graph -- the only way to see index
  shrinkage, which no protocol read can observe (an emptied inner map
  answers `edges-to` exactly as an absent one does)."
  memory-index/internal-state)

(defn- book [lib slug] {:library lib :shelf [] :slug slug})

(deftest without-book-shrinks-the-indexes-test
  (let [kept (book "lib" "kept")
        gone (book "lib" "gone")
        g0 (-> (memory-index/memory-index)
               (graph/with-book (contract/record kept {:hash "k" :files #{"kept.hdoc" "shared.hdoc"}
                                                       :edges [(contract/edge kept {:to-id gone})]})))
        ;; `gone`'s own targets and file have no other source; it also
        ;; shares a target key (`kept`) and a file with nothing else, and a
        ;; file with `kept`
        record (contract/record gone
                                {:hash "g"
                                 :files #{"gone.hdoc" "shared.hdoc"}
                                 :edges [(contract/edge gone {:to-id (book "other" "x")})
                                         (contract/edge gone {:written "nowhere:y" :problem :unknown-library})
                                         (contract/edge gone {:to-id kept})]})
        g1 (graph/with-book g0 record)]
    (testing "the round trip actually indexed something new"
      (is (not= (state g0) (state g1)))
      (is (contains? (:targets (state g1)) (graph/target-key (first (:edges record)))))
      (is (contains? (:files (state g1)) "gone.hdoc")))
    (testing "with-book then without-book of a book leaves the internal state
              exactly as before: no emptied inner map or set is left behind,
              while keys other books still use are kept"
      (is (= (state g0) (state (graph/without-book g1 gone)))))
    (testing "and so does replacing the book with a record that drops them"
      (is (= (state g0)
             (state (-> g1
                        (graph/with-book (contract/record gone {:hash "g2"}))
                        (graph/without-book gone))))))))

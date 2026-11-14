(ns haselnuss.library.graph.libraries-test
  "Library records (TASK-241): `library-indexed` keeps them beside any
  backend's book records without changing what the six portable
  operations answer."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.contract :as contract]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.memory-index :as memory-index]))

(deftest wrapped-graph-passes-the-graph-contract-test
  (let [counts (contract/check-graph-contract
                {:new-graph (comp libraries/library-indexed memory-index/memory-index)})]
    (is (zero? (:mismatches counts)))))

(def ^:private lib-a {:name "a" :status :available :root "/x/a" :diagnostics []})

(def ^:private lib-b
  {:name "b" :status :unavailable
   :diagnostics [{:type :unavailable-library :level :warning :library "b" :message "gone"}]})

(def ^:private book
  {:id (book-ref/book-id "a" "one")
   :hash "h"
   :files #{"/x/a/one.hdoc"}
   :labels {}
   :edges [{:from {:id (book-ref/book-id "a" "one")}
            :to {:written "b:two"}
            :kind :link :status :dangling :problem :unavailable-library}]
   :diagnostics []})

(deftest library-records-test
  (let [g0 (libraries/library-indexed (memory-index/memory-index))
        g1 (graph/with-library g0 lib-a)
        g2 (graph/with-library g1 lib-b)
        g3 (graph/with-book g2 book)]
    (testing "a wrapped graph satisfies both protocols, and wrapping is idempotent"
      (is (satisfies? graph/ReferenceGraph g0))
      (is (satisfies? graph/LibraryIndex g0))
      (is (identical? g3 (libraries/library-indexed g3))))
    (testing "with-library adds or replaces one record; a value never changes"
      (is (= {} (graph/indexed-libraries g0)))
      (is (= {"a" lib-a} (graph/indexed-libraries g1)))
      (is (= {"a" lib-a "b" lib-b} (graph/indexed-libraries g2)))
      (is (= {"a" (assoc lib-a :diagnostics (:diagnostics lib-b)) "b" lib-b}
             (graph/indexed-libraries
              (graph/with-library g2 (assoc lib-a :diagnostics (:diagnostics lib-b))))))
      (is (= {"b" lib-b} (graph/indexed-libraries (graph/without-library g3 "a"))))
      (is (= {"a" lib-a "b" lib-b} (graph/indexed-libraries g3))))
    (testing "book writes keep the library records, and library writes the book records"
      (is (= {"a" lib-a "b" lib-b} (graph/indexed-libraries g3)))
      (is (= {"a" lib-a "b" lib-b}
             (graph/indexed-libraries (graph/without-book g3 (:id book)))))
      (is (= #{(:id book)} (set (keys (graph/indexed-books (graph/without-library g3 "a")))))))
    (testing "a library record is invisible to the six operations"
      (is (= [(:id book)] (keys (graph/indexed-books g3))))
      (is (= (:edges book) (graph/edges-from g3 (:id book))))
      (is (= (:edges book) (graph/edges-to g3 (book-ref/book-id "b" "two"))))
      (is (= #{} (graph/books-for-file g3 "/x/a")))
      (is (= #{(:id book)} (graph/books-for-file g3 "/x/a/one.hdoc"))))
    (testing "backend-graph is the wrapped backend, with every book write applied"
      (let [inner (libraries/backend-graph g3)]
        (is (not (satisfies? graph/LibraryIndex inner)))
        (is (= (graph/indexed-books g3) (graph/indexed-books inner))))
      (let [plain (memory-index/memory-index)]
        (is (identical? plain (libraries/backend-graph plain)))))
    (testing "an invalid library record is refused, naming the problem"
      (doseq [bad [{} (dissoc lib-a :status) (assoc lib-a :name "Bad Name")
                   (assoc lib-a :status :maybe) (assoc lib-a :diagnostics [{:type :x}])
                   (assoc lib-a :root 3)]]
        (let [e (try (graph/with-library g0 bad) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::graph/invalid-library-record (:type (ex-data e))) (pr-str bad))
          (is (some? (:explain (ex-data e)))))))
    (testing "GraphQuery is delegated to the backend, and refused clearly when it has none"
      (is (satisfies? graph/GraphQuery g3))
      (let [e (try (graph/query g3 '[:find ?e] {}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::libraries/query-unsupported (:type (ex-data e)))))
      (let [seen (atom nil)
            queryable (fn queryable [inner]
                        (reify
                          graph/ReferenceGraph
                          (with-book [_ r] (queryable (graph/with-book inner r)))
                          (without-book [_ id] (queryable (graph/without-book inner id)))
                          (indexed-books [_] (graph/indexed-books inner))
                          (edges-from [_ id] (graph/edges-from inner id))
                          (edges-to [_ t] (graph/edges-to inner t))
                          (books-for-file [_ p] (graph/books-for-file inner p))
                          graph/GraphQuery
                          (query [_ q inputs]
                            (reset! seen [q inputs])
                            (count (graph/indexed-books inner)))))
            w (-> (libraries/library-indexed (queryable (memory-index/memory-index)))
                  (graph/with-library lib-a)
                  (graph/with-book book))]
        (is (= 1 (graph/query w :count-books {:x 1})) "answered by the backend, after a book write")
        (is (= [:count-books {:x 1}] @seen))))
    (testing "only a ReferenceGraph can be wrapped"
      (is (thrown? clojure.lang.ExceptionInfo (libraries/library-indexed {}))))))

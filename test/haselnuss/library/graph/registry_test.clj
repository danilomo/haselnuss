(ns haselnuss.library.graph.registry-test
  "The graph backend registry (TASK-238 AC #2): `:memory-index` is
  registered and the default, backends can be added, and an unknown
  backend or a constructor that builds no graph is a clear error."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.registry :as registry]))

(defn- thrown-data
  "The `ex-data` and message of what `f` throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) :message (ex-message e)))))

(deftest default-backend-test
  (testing ":memory-index is registered and is the default"
    (is (= :memory-index registry/default-backend))
    (is (contains? registry/default-registry :memory-index))
    (let [g (registry/new-graph)]
      (is (satisfies? graph/ReferenceGraph g))
      (is (= {} (graph/indexed-books g))))
    (is (satisfies? graph/ReferenceGraph (registry/new-graph :memory-index)))))

(deftest unknown-backend-test
  (let [data (thrown-data #(registry/new-graph :datascript))]
    (is (= ::registry/unknown-backend (:type data)))
    (is (= :datascript (:backend data)))
    (is (= "unknown graph backend :datascript; known: :memory-index" (:message data)))))

(deftest register-backend-test
  (let [seen (atom nil)
        reg (registry/register-backend registry/default-registry :custom
                                       (fn [config] (reset! seen config)
                                         (registry/new-graph)))]
    (testing "a registered backend is built with its config"
      (is (satisfies? graph/ReferenceGraph (registry/new-graph reg :custom {:x 1})))
      (is (= {:x 1} @seen)))
    (testing "the error names every known backend"
      (is (= "unknown graph backend :nope; known: :custom, :memory-index"
             (:message (thrown-data #(registry/new-graph reg :nope nil)))))))
  (testing "bad names and constructors are refused"
    (doseq [bad [" " "custom" 'custom nil]]
      (is (= ::registry/invalid-backend-name
             (:type (thrown-data #(registry/register-backend {} bad (fn [_])))))
          (str "a non-keyword name is refused: " (pr-str bad))))
    (is (= "a graph backend name must be a keyword, got \"custom\""
           (:message (thrown-data #(registry/register-backend {} "custom" (fn [_]))))))
    (is (= ::registry/invalid-backend
           (:type (thrown-data #(registry/register-backend {} :x 42))))))
  (testing "a constructor that builds no graph is an error naming the backend"
    (let [data (thrown-data #(registry/new-graph {:broken (fn [_] {})} :broken nil))]
      (is (= ::registry/invalid-backend (:type data)))
      (is (= "graph backend :broken did not build a ReferenceGraph" (:message data))))))

(ns haselnuss.library.graph-test
  "The book record and edge schemas and the shared matching rules of
  `haselnuss.library.graph` (TASK-238 AC #1)."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.contract :as contract]
            [malli.core :as m]))

(deftest book-record-schema-test
  (testing "the contract's fixtures are valid records"
    (doseq [r [contract/heidelberg-v1 contract/heidelberg-v2 contract/apostles-v1]]
      (is (graph/valid-book-record? r) (pr-str (graph/explain-book-record r)))))
  (testing "each field is required and checked"
    (doseq [k [:id :hash :files :labels :edges :diagnostics]]
      (is (not (graph/valid-book-record? (dissoc contract/heidelberg-v1 k))) (str k)))
    (is (not (graph/valid-book-record? (assoc contract/heidelberg-v1 :files ["a"])))
        ":files is a set")
    (is (not (graph/valid-book-record? (assoc contract/heidelberg-v1 :diagnostics [{:type :x}])))
        "a diagnostic needs a :message")
    (is (not (graph/valid-book-record?
              (assoc contract/heidelberg-v1 :id (book-ref/book-id "Bad" "x"))))
        "the id must be a well-formed BookId"))
  (testing "an edge must come from its record's book"
    (is (not (graph/valid-book-record?
              (update contract/heidelberg-v1 :edges conj
                      (contract/edge contract/apostles {:to-id contract/romanos})))))))

(deftest edge-schema-test
  (let [ok (contract/edge contract/heidelberg {:to-id contract/romanos})]
    (is (m/validate graph/Edge ok))
    (testing "status and problem agree"
      (is (not (m/validate graph/Edge (assoc ok :problem :unknown-label)))
          "a resolved edge has no problem")
      (is (not (m/validate graph/Edge (assoc ok :status :dangling)))
          "a dangling edge has a problem")
      (is (not (m/validate graph/Edge (update ok :to dissoc :id)))
          "a resolved edge has a target id")
      (is (m/validate graph/Edge (-> ok (update :to dissoc :id)
                                     (assoc :status :dangling :problem :unknown-book)))))
    (testing "kind, passage and position are checked"
      (is (not (m/validate graph/Edge (assoc ok :kind :cite))))
      (is (not (m/validate graph/Edge (assoc-in ok [:to :passage] "14:8"))))
      (is (m/validate graph/Edge (assoc-in ok [:to :passage] {:form :range :from "sec:a" :to "sec:b"})))
      (is (m/validate graph/Edge (dissoc ok :position))
          "a reference with no recorded position (a generated book's) has none (TASK-240)")
      (is (not (m/validate graph/Edge (assoc ok :position {:file "a.hdoc"})))
          "a position, when given, has a line")
      (is (not (m/validate graph/Edge (assoc ok :position {:line 0})))))))

(deftest check-book-record-test
  (let [e (try (graph/check-book-record! (assoc contract/heidelberg-v1 :hash 1))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::graph/invalid-book-record (:type (ex-data e))))
    (is (= "not a valid book record for reformed:confessions/reformed/heidelberg"
           (ex-message e)))
    (is (some? (:explain (ex-data e))))))

(deftest passage-key-test
  (is (= "14:8" (graph/passage-key {:form :locator :locator "14:8"})))
  (is (= "sec:a..sec:b" (graph/passage-key {:form :range :from "sec:a" :to "sec:b"})))
  (is (= "14:8" (graph/passage-key " 14:8  \n")))
  (is (nil? (graph/passage-key nil))))

(deftest edge-passage-key-test
  (let [written-only (contract/edge contract/heidelberg {:written "bible:nope#2:3"
                                                         :problem :unknown-book})]
    (is (nil? (get-in written-only [:to :passage])))
    (is (= "2:3" (graph/edge-passage-key written-only))
        "falls back to the passage in :written")
    (is (= "14:8" (graph/edge-passage-key
                   (contract/edge contract/heidelberg {:to-id contract/romanos :written "bible:romanos#9:9"
                                                       :passage {:form :locator :locator "14:8"}})))
        ":to :passage wins over :written")
    (is (nil? (graph/edge-passage-key (contract/edge contract/heidelberg {:to-id contract/romanos}))))))

(deftest parse-target-test
  (doseq [bad [{:form :label} {:form :range :from "sec:a"} {:form :locator} 42]]
    (is (= ::graph/invalid-target
           (try (graph/parse-target {:id contract/romanos :passage bad}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        (pr-str bad))))

(deftest edge-matches-target-test
  (let [to-a (contract/edge contract/heidelberg {:to-id contract/same-a})
        dangling-a (contract/edge contract/heidelberg {:to-id contract/same-a
                                                       :passage {:form :label :label "sec:z"}
                                                       :problem :unknown-label})
        matches? (fn [target e] (graph/edge-matches-target? (graph/parse-target target) e))]
    (is (matches? contract/same-a to-a))
    (is (not (matches? contract/same-b to-a)) "a resolved edge into another shelf's book")
    (is (matches? (book-ref/book-id "shared" "same") to-a) "a short-form query")
    (is (matches? contract/same-b dangling-a) "dangling edges match by slug")))

(deftest target-key-test
  (let [nope (book-ref/book-id "bible" "nope")]
    (is (= ["bible" "romanos"]
           (graph/target-key (contract/edge contract/heidelberg {:to-id contract/romanos
                                                                 :written "bible:other"})))
        "a resolved id wins over what was written")
    (is (= (book-ref/book-key nope)
           (graph/target-key (contract/edge contract/heidelberg {:written "bible:x/nope#1:1"
                                                                 :problem :unknown-book}))))
    (is (nil? (graph/target-key (contract/edge contract/heidelberg {:written "bible:"
                                                                    :problem :malformed-book-ref}))))))

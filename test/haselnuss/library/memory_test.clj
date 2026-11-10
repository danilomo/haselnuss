(ns haselnuss.library.memory-test
  "The `:memory` provider (TASK-149 AC #3): it passes the shared provider
  contract (`haselnuss.library.contract`, AC #4), listing never calls a
  book's `:document`, books are found by book-key whatever shelf a
  reference writes, a slug collision answers `:ambiguous-book` and is
  reported as a `:duplicate-slug` library diagnostic, a ready-made Book's
  entry is checked, and a bad config is a diagnostic rather than a throw."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.contract :as contract]
            [haselnuss.library.memory :as memory]))

(defn- counted-sample-library
  "A memory library built from the contract's sample, and a fn answering
  how many documents it has loaded."
  []
  (let [loads (atom 0)
        lib (memory/memory-library
             {:name "reformed"
              :books (contract/sample-books contract/sample-expectation
                                            #(swap! loads inc))})]
    [lib (fn [] @loads)]))

(deftest provider-contract-test
  (let [[lib loads] (counted-sample-library)]
    (contract/check-provider-contract
     {:library lib
      :expected contract/sample-expectation
      :document-loads loads})))

(defn- contract-failures
  "The `:fail`/`:error` reports the contract makes against `fixture`,
  captured instead of counted against this test."
  [fixture]
  (let [reports (atom [])]
    (binding [t/report (fn [m] (when (#{:fail :error} (:type m)) (swap! reports conj m)))]
      (contract/check-provider-contract fixture))
    @reports))

(deftest contract-rejects-a-broken-provider-test
  (let [[good loads] (counted-sample-library)
        eager (reify library/Library
                (library-name [_] (library/library-name good))
                (shelf-tree [_] (library/shelf-tree good))
                (list-books [_]
                  (run! #(library/document (library/find-book good (:slug (:id %))))
                        (library/list-books good))
                  (library/list-books good))
                (find-book [_ slug] (library/find-book good slug)))
        shelf-keyed (reify library/Library
                      (library-name [_] "reformed")
                      (shelf-tree [_] {})
                      (list-books [_] (library/list-books good))
                      (find-book [_ slug] (when (= slug "notes")
                                            (library/find-book good slug))))]
    (testing "a provider whose listing loads documents fails"
      (is (seq (contract-failures {:library eager
                                   :expected contract/sample-expectation
                                   :document-loads loads}))))
    (testing "a provider that cannot find shelved books fails"
      (is (seq (contract-failures {:library shelf-keyed
                                   :expected contract/sample-expectation}))))
    (testing "a provider that rebuilds a Book (and reloads it) on every find-book fails"
      (let [loads (atom 0)
            spec-for (into {} (map (juxt (comp :slug :id) identity))
                           (contract/sample-books contract/sample-expectation
                                                  #(swap! loads inc)))
            uncached (reify library/Library
                       (library-name [_] "reformed")
                       (shelf-tree [_] (library/shelf-tree good))
                       (list-books [_] (library/list-books good))
                       (find-book [_ slug]
                         (some-> (get spec-for slug) library/virtual-book)))]
        (is (seq (contract-failures {:library uncached
                                     :expected contract/sample-expectation
                                     :document-loads (fn [] @loads)})))))
    (testing "on a collision, a provider that answers the first book fails"
      (let [colliding (memory/memory-library
                       {:name "shared"
                        :books (contract/sample-books contract/collision-expectation)})
            first-wins (reify library/Library
                         (library-name [_] "shared")
                         (shelf-tree [_] (library/shelf-tree colliding))
                         (list-books [_] (library/list-books colliding))
                         (find-book [_ slug]
                           (let [found (library/find-book colliding slug)]
                             (if (library/diagnostic? found)
                               (library/find-book good "notes")
                               found))))]
        (is (seq (contract-failures {:library first-wins
                                     :expected contract/collision-expectation})))))))

(deftest collision-contract-test
  (contract/check-provider-contract
   {:library (memory/memory-library
              {:name "shared"
               :books (contract/sample-books contract/collision-expectation)})
    :expected contract/collision-expectation}))

(deftest empty-library-contract-test
  (contract/check-provider-contract
   {:library (memory/memory-library {:name "empty" :books []})
    :expected {:name "empty" :books [] :shelf-tree {}}}))

(deftest listing-never-loads-a-document-test
  (let [[lib loads] (counted-sample-library)]
    (is (= 4 (count (library/list-books lib))))
    (is (= {"confessions" {"reformed" {}} "creeds" {}} (library/shelf-tree lib)))
    (is (zero? (loads)) "list-books and shelf-tree called no :document")
    (library/document (library/find-book lib "heidelberg"))
    (is (= 1 (loads)))))

(deftest accepts-built-books-test
  (testing "an item that already satisfies Book is held as is"
    (let [book (library/virtual-book
                {:id (book-ref/book-id "bible" ["scripture" "nt"] "romanos")
                 :entry {:title "Romanos"}
                 :document (fn [] {:meta {} :blocks []})})
          lib (memory/memory-library {:name "bible" :books [book]})]
      (is (identical? book (library/find-book lib "romanos"))))))

(deftest find-book-uses-book-key-test
  (let [lib (memory/memory-library
             {:name "reformed"
              :books (contract/sample-books contract/sample-expectation)})
        current (book-ref/book-id "reformed" ["confessions" "reformed"] "heidelberg")]
    (doseq [written [(book-ref/book-id "reformed" "heidelberg")
                     (book-ref/book-id "reformed" ["old" "shelf"] "heidelberg")]]
      (testing (book-ref/format-book-id written)
        (let [book (library/find-book lib (:slug written))]
          (is (book-ref/same-book? written (:id (library/book-entry book))))
          (is (= current (:id (library/book-entry book)))
              "the entry reports the book's current shelf, not the written one"))))))

(deftest duplicate-slug-test
  (let [lib (memory/memory-library
             {:name "lib"
              :books [{:id (book-ref/book-id "lib" ["a"] "same") :document (constantly nil)}
                      {:id (book-ref/book-id "lib" ["b"] "same") :document (constantly nil)}
                      {:id (book-ref/book-id "lib" "other") :document (constantly nil)}]})
        found (library/find-book lib "same")]
    (is (= 3 (count (library/list-books lib))) "both colliding books are listed")
    (is (= :ambiguous-book (:type found)))
    (is (library/diagnostic? found))
    (is (= ["lib:a/same" "lib:b/same"] (:books found)))
    (is (satisfies? library/Book (library/find-book lib "other"))
        "other slugs are unaffected")
    (testing "the collision is a :duplicate-slug library diagnostic naming both books"
      (let [[d & more] (library/library-diagnostics lib)]
        (is (nil? more))
        (is (= {:type :duplicate-slug :library "lib" :slug "same"
                :books ["lib:a/same" "lib:b/same"]}
               (dissoc d :message)))
        (is (library/diagnostic? d))))
    (testing "a library without collisions reports none"
      (is (= [] (library/library-diagnostics
                 (memory/memory-library {:name "lib" :books []})))))))

(defrecord ^:private PrebuiltBook [entry]
  library/Book
  (book-entry [_] entry)
  (document [_] {:meta {} :blocks []}))

(deftest prebuilt-book-entry-is-checked-test
  (let [id (book-ref/book-id "bible" "romanos")]
    (testing "a ready-made Book whose entry is already a BookEntry is accepted"
      (let [book (->PrebuiltBook {:id id :title [{:t :str :text "Romanos"}] :lang "pt-BR"})]
        (is (identical? book (library/find-book
                              (memory/memory-library {:name "bible" :books [book]})
                              "romanos")))))
    (doseq [[label entry] {"string title" {:id id :title "Romanos"}
                           "numeric lang" {:id id :title [{:t :str :text "R"}] :lang 5}
                           "tags not a vector" {:id id :title [{:t :str :text "R"}]
                                                :tags '("nt")}
                           "no id" {:title [{:t :str :text "R"}]}
                           "not a map" "romanos"}]
      (testing label
        (let [result (memory/memory-library {:name "bible" :books [(->PrebuiltBook entry)]})]
          (is (= :invalid-library (:type result)))
          (is (library/diagnostic? result)))))
    (testing "a ready-made Book whose book-entry throws"
      (let [result (memory/memory-library
                    {:name "bible"
                     :books [(reify library/Book
                               (book-entry [_] (throw (ex-info "no entry" {})))
                               (document [_] nil))]})]
        (is (= :invalid-library (:type result)))
        (is (re-find #"no entry" (:message result)))))))

(deftest invalid-config-test
  (doseq [[label config] {"not a map" "bible"
                          "bad name" {:name "Bible" :books []}
                          "missing name" {:books []}
                          "books not a seq" {:name "bible" :books 3}
                          "item not a book" {:name "bible" :books [42]}
                          "invalid spec" {:name "bible" :books [{:id "bible:x"}]}
                          "book of another library"
                          {:name "bible"
                           :books [{:id (book-ref/book-id "other" "x")
                                    :document (constantly nil)}]}}]
    (testing label
      (let [result (memory/memory-library config)]
        (is (= :invalid-library (:type result)))
        (is (library/diagnostic? result))))))

(deftest directory-of-memory-libraries-test
  (let [bible (memory/memory-library {:name "bible" :books []})
        reformed (memory/memory-library {:name "reformed" :books []})
        dir (library/library-directory [reformed bible])]
    (is (= ["reformed" "bible"] (library/library-names dir)) "directory order")
    (is (identical? bible (library/find-library dir "bible")))
    (is (nil? (library/find-library dir "nope")))
    (testing "two libraries with one name"
      (let [result (library/library-directory
                    [bible (memory/memory-library {:name "bible" :books []})])]
        (is (= :duplicate-library (:type result)))
        (is (= :error (:level result)))
        (is (library/diagnostic? result))))
    (testing "a non-library item"
      (is (= :invalid-library-directory
             (:type (library/library-directory [bible {:name "x"}])))))
    (testing "a library that failed to build keeps its own diagnostic under :cause"
      (let [failed (memory/memory-library {:name "Bad" :books []})
            result (library/library-directory [bible failed])]
        (is (= :invalid-library-directory (:type result)))
        (is (= failed (:cause result)))
        (is (library/diagnostic? result))))
    (testing "a directory with no failures reports no diagnostics"
      (is (= [] (vec (library/library-diagnostics dir)))))))

(deftest directory-with-failures-test
  (let [bible (memory/memory-library {:name "bible" :books []})
        failure {:type :invalid-library :level :error :library "reformed" :message "boom"}
        later {:type :unknown-provider :level :error :library "reformed" :message "again"}
        dir (library/library-directory [bible] [failure later])]
    (is (= ["bible"] (library/library-names dir)) "a failed library is not listed")
    (is (identical? bible (library/find-library dir "bible")))
    (is (= [failure later] (vec (library/library-diagnostics dir))))
    (testing "a failed library answers :unavailable-library with its first failure"
      (let [found (library/find-library dir "reformed")]
        (is (= :unavailable-library (:type found)))
        (is (= :warning (:level found)))
        (is (= "reformed" (:library found)))
        (is (= failure (:cause found)))
        (is (library/diagnostic? found))))
    (is (nil? (library/find-library dir "nope")))
    (testing "a failure that is not a diagnostic naming a separate library is rejected"
      (doseq [bad [42
                   {:type :x :message "no library"}
                   {:type :x :message "bad name" :library "Bad Name"}
                   {:type :x :message "loaded too" :library "bible"}]]
        (is (= [:invalid-library-directory :error]
               ((juxt :type :level) (library/library-directory [bible] [bad])))
            (pr-str bad))))))

(deftest directory-url-templates-test
  (let [bible (memory/memory-library {:name "bible" :books []})]
    (testing "a directory answers the URL template given for each library (TASK-155)"
      (let [dir (library/library-directory [bible] [] {:url-templates {"bible" "https://b/{slug}"}})]
        (is (satisfies? library/UrlTemplates dir))
        (is (= "https://b/{slug}" (library/url-template dir "bible")))
        (is (nil? (library/url-template dir "other")))))
    (testing "and none by default"
      (is (nil? (library/url-template (library/library-directory [bible]) "bible"))))
    (testing "an invalid template, a bad name or a non-map is rejected"
      (doseq [bad [{"bible" "https://b/{verse}"} {"bible" 42} {"Bad Name" "https://b/"} [1]]]
        (let [result (library/library-directory [bible] [] {:url-templates bad})]
          (is (= [:invalid-library-directory :error] ((juxt :type :level) result)) (pr-str bad))
          (is (library/diagnostic? result)))))))

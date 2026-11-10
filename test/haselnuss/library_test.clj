(ns haselnuss.library-test
  "The library protocols and `virtual-book` (TASK-149 AC #1, #2): a
  virtual book's `:document` is called lazily and at most once, it is
  Locatable exactly when `:locate` is given, and every failure -- a bad
  spec, a throwing or invalid `:document`/`:locate` -- is a `{:type
  :message}` diagnostic rather than an exception."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]))

(def ^:private romanos (book-ref/book-id "bible" ["scripture" "nt"] "romanos"))

(def ^:private doc
  {:meta {} :blocks [{:t :para :inlines [{:t :str :text "verse"}]}]})

(defn- doc-fn [] doc)

(deftest protocols-test
  (testing "the four protocols exist with doc-10's methods"
    (is (= #{:library-names :find-library}
           (set (keys (:sigs library/LibraryDirectory)))))
    (is (= #{:library-name :shelf-tree :list-books :find-book}
           (set (keys (:sigs library/Library)))))
    (is (= #{:book-entry :document} (set (keys (:sigs library/Book)))))
    (is (= #{:locate} (set (keys (:sigs library/Locatable))))))
  (testing "the optional scan-diagnostics protocol"
    (is (= #{:library-diagnostics} (set (keys (:sigs library/LibraryDiagnostics)))))))

(defn- within
  "`(f)`, or ::timeout when it does not return within `ms` -- so a value
  that would hang `document` fails the test instead of the suite."
  [ms f]
  (deref (future (f)) ms ::timeout))

(deftest lazy-values-never-escape-test
  (let [lazy-boom (fn [] (map (fn [_] (throw (ex-info "lazy boom" {}))) [1]))]
    (doseq [[label make-doc]
            {"throwing lazy :blocks" #(hash-map :meta {} :blocks (lazy-boom))
             "throwing lazy :inlines inside a block vector"
             #(hash-map :meta {} :blocks [{:t :para :inlines (lazy-boom)}])
             "a throwing lazy seq where a block should be"
             #(hash-map :meta {} :blocks [(lazy-boom)])
             "infinite :blocks" #(hash-map :meta {} :blocks (range))
             "infinite :inlines" #(hash-map :meta {} :blocks [{:t :para :inlines (range)}])
             "an infinite seq where a block should be"
             #(hash-map :meta {} :blocks [(range)])}]
      (testing label
        (let [result (within 5000 #(library/document
                                    (library/virtual-book {:id romanos :document make-doc})))]
          (is (not= ::timeout result))
          (is (= :invalid-book-document (:type result)))
          (is (library/diagnostic? result))
          (is (< (count (:message result)) 600) "the message is bounded")))))
  (testing "a :locate result with lazy or infinite blocks"
    (let [book (library/virtual-book
                {:id romanos
                 :document (constantly doc)
                 :locate (fn [loc]
                           (case loc
                             "boom" {:blocks (map (fn [_] (throw (ex-info "lazy boom" {}))) [1])}
                             "nested" {:blocks [{:t :para
                                                 :inlines (map (fn [_] (throw (ex-info "x" {}))) [1])}]}
                             "inf" {:blocks [(range)]}))})]
      (doseq [loc ["boom" "nested" "inf"]]
        (let [result (within 5000 #(library/locate book loc))]
          (is (= :invalid-book-document (:type result)) loc)
          (is (library/diagnostic? result) loc))))))

(deftest virtual-book-entry-test
  (let [book (library/virtual-book {:id romanos
                                    :entry {:title "Romanos" :lang "pt-BR"
                                            :tags '("nt") :extra 1}
                                    :document (constantly doc)})]
    (is (satisfies? library/Book book))
    (is (= {:id romanos :title [{:t :str :text "Romanos"}] :lang "pt-BR"
            :tags ["nt"]}
           (library/book-entry book))
        "string title becomes inlines; unknown keys are dropped"))
  (testing "a missing title defaults to the slug"
    (is (= [{:t :str :text "romanos"}]
           (:title (library/book-entry
                    (library/virtual-book {:id romanos :document (constantly doc)})))))))

(deftest document-is-lazy-and-cached-test
  (let [calls (atom 0)
        book (library/virtual-book {:id romanos
                                    :document (fn [] (swap! calls inc) doc)})]
    (library/book-entry book)
    (is (zero? @calls) "building and reading the entry does not call :document")
    (is (= doc (library/document book)))
    (is (= doc (library/document book)))
    (is (= 1 @calls) "called once however often document is asked for")
    (testing "concurrent first calls still run it once"
      (let [calls (atom 0)
            book (library/virtual-book {:id romanos
                                        :document (fn [] (swap! calls inc)
                                                    (Thread/sleep 20) doc)})]
        (run! deref (doall (repeatedly 8 #(future (library/document book)))))
        (is (= 1 @calls))))
    (testing "each virtual-book call builds a fresh cache"
      (let [calls (atom 0)
            spec {:id romanos :document (fn [] (swap! calls inc) doc)}]
        (library/document (library/virtual-book spec))
        (library/document (library/virtual-book spec))
        (is (= 2 @calls))))))

(deftest document-failure-is-a-diagnostic-test
  (testing "a throwing :document"
    (let [calls (atom 0)
          book (library/virtual-book {:id romanos
                                      :document (fn [] (swap! calls inc)
                                                  (throw (ex-info "boom" {})))})
          result (library/document book)]
      (is (= :book-failed (:type result)))
      (is (library/diagnostic? result))
      (is (re-find #"bible:scripture/nt/romanos.*boom" (:message result)))
      (library/document book)
      (is (= 1 @calls) "the failure is cached too")))
  (testing "an invalid document"
    (let [result (library/document
                  (library/virtual-book {:id romanos
                                         :document (constantly {:blocks [{:t :nope}]})}))]
      (is (= :invalid-book-document (:type result)))
      (is (library/diagnostic? result))))
  (testing "a :document fn that returns a diagnostic passes it through"
    (let [diag {:type :custom :message "unavailable"}]
      (is (= diag (library/document
                   (library/virtual-book {:id romanos :document (constantly diag)})))))))

(deftest locatable-test
  (testing "without :locate the book is not Locatable"
    (is (not (satisfies? library/Locatable
                         (library/virtual-book {:id romanos :document (constantly doc)})))))
  (let [book (library/virtual-book
              {:id romanos
               :document (constantly doc)
               :locate (fn [locator]
                         (case locator
                           "14:8" {:title "Romanos 14:8" :blocks (:blocks doc)}
                           "bad" {:type :bad-locator :message "no such verse"}
                           "throws" (throw (ex-info "chapter 99 does not exist" {}))
                           "junk" {:blocks "not blocks"}
                           "own-ref" {:ref 42 :extra 1 :url "https://x.org/r#14:9"
                                      :blocks []}))})]
    (is (satisfies? library/Locatable book))
    (testing "a passage gets its canonical :ref and inline :title"
      (is (= {:ref "bible:scripture/nt/romanos#14:8"
              :title [{:t :str :text "Romanos 14:8"}]
              :blocks (:blocks doc)}
             (library/locate book "14:8"))))
    (testing "a rejected locator is the book's own diagnostic"
      (is (= {:type :bad-locator :message "no such verse"} (library/locate book "bad"))))
    (testing "a throwing :locate becomes :bad-locator carrying its message"
      (let [result (library/locate book "throws")]
        (is (= :bad-locator (:type result)))
        (is (re-find #"chapter 99 does not exist" (:message result)))))
    (testing "an invalid passage is a diagnostic"
      (is (= :invalid-book-document (:type (library/locate book "junk")))))
    (testing ":ref is always canonical and unknown keys are dropped"
      (is (= {:ref "bible:scripture/nt/romanos#own-ref"
              :title [{:t :str :text "bible:scripture/nt/romanos#own-ref"}]
              :blocks []
              :url "https://x.org/r#14:9"}
             (library/locate book "own-ref"))))
    (testing "a nil, empty or non-string locator is rejected without calling :locate"
      (doseq [loc [nil "" 14]]
        (let [result (library/locate book loc)]
          (is (= :bad-locator (:type result)) (pr-str loc))
          (is (library/diagnostic? result)))))))

(deftest any-ifn-is-accepted-test
  (let [book (library/virtual-book {:id romanos :document #'doc-fn})]
    (is (satisfies? library/Book book))
    (is (= doc (library/document book)))))

(deftest invalid-spec-test
  (doseq [[label spec] {"not a map" "bible:romanos"
                        "bad id" {:id "bible:romanos" :document (constantly doc)}
                        "id with bad slug" {:id (book-ref/book-id "bible" "Romanos")
                                            :document (constantly doc)}
                        "no document fn" {:id romanos}
                        "locate not a fn" {:id romanos :document (constantly doc) :locate 1}
                        "bad title" {:id romanos :entry {:title 7} :document (constantly doc)}
                        "bad tags" {:id romanos :entry {:tags "nt"} :document (constantly doc)}}]
    (testing label
      (let [result (library/virtual-book spec)]
        (is (= :invalid-virtual-book (:type result)))
        (is (library/diagnostic? result))))))

(deftest shelf-tree-of-test
  (is (= {} (library/shelf-tree-of [])))
  (is (= {"a" {"b" {} "c" {}} "d" {}}
         (library/shelf-tree-of (for [shelf [["a" "b"] ["a" "c"] ["d"] [] ["a"]]]
                                  {:id (book-ref/book-id "l" shelf "s")})))))

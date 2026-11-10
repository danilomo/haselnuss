(ns haselnuss.library.url-test
  "URL templates (TASK-155, doc-10 \"Resolver\": URLs): every placeholder
  filled and percent-encoded, and a template with an unknown placeholder
  or a stray brace refused."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.url :as url]))

(def ^:private heidelberg (book-ref/book-id "reformed" ["confessions" "reformed"] "heidelberg"))

(def ^:private every-placeholder
  "https://example.org/{library}/{shelf}/{slug}.html?at={locator}#{label}")

(deftest fills-every-placeholder-test
  (testing "a label"
    (is (= "https://example.org/reformed/confessions/reformed/heidelberg.html?at=#sec:q1"
           (url/fill every-placeholder heidelberg {:form :label :label "sec:q1"}))))
  (testing "a range lands on its first label"
    (is (= "https://example.org/reformed/heidelberg.html#sec:q1"
           (url/fill "https://example.org/{library}/{slug}.html#{label}" heidelberg
                     {:form :range :from "sec:q1" :to "sec:q3"}))))
  (testing "a locator"
    (is (= "https://example.org/bible/nt/romanos.html?at=14:8-9"
           (url/fill every-placeholder (book-ref/book-id "bible" ["nt"] "romanos")
                     {:form :locator :locator "14:8-9"}))))
  (testing "the whole book: {label} and {locator} are empty, and an empty fragment is dropped"
    (is (= "https://example.org/reformed/heidelberg.html"
           (url/fill "https://example.org/{library}/{slug}.html#{label}" heidelberg nil))))
  (testing "a book at the library's root has an empty {shelf}"
    (is (= "https://example.org/b/romanos"
           (url/fill "https://example.org/b/{shelf}romanos" (book-ref/book-id "bible" "romanos") nil))))
  (testing "a placeholder may repeat, and the template's own text is left as written"
    (is (= "/r/heidelberg/heidelberg?q=%7Bx"
           (url/fill "/r/{slug}/{slug}?q=%7Bx" heidelberg nil)))))

(deftest encodes-every-value-test
  (let [romanos (book-ref/book-id "bible" ["nt"] "romanos")
        fill (fn [locator] (url/fill "https://e.org/{slug}?v={locator}" romanos
                                     {:form :locator :locator locator}))]
    (testing "unreserved characters and : stay as they are"
      (is (= "https://e.org/romanos?v=aZ09-._~:" (fill "aZ09-._~:"))))
    (testing "anything that could change the URL's structure is encoded"
      (is (= "https://e.org/romanos?v=a%2Fb%3Fc%26d%3De%2Bf%25g%40h%27i" (fill "a/b?c&d=e+f%g@h'i"))))
    (testing "non-ASCII is encoded as UTF-8"
      (is (= "https://e.org/romanos?v=G%C3%AAnesis%E2%80%941" (fill "Gênesis—1"))))
    (testing "{shelf} encodes each segment and keeps the slashes between them"
      (is (= "x/confessions/reformed/y"
             (url/fill "x/{shelf}/y" heidelberg nil))))
    (testing "encode on its own"
      (is (= "sec:q1" (url/encode "sec:q1")))
      (is (= "a%20b" (url/encode "a b"))))))

(deftest empty-shelf-test
  (let [root (book-ref/book-id "reformed" "heidelberg")]
    (testing "an empty {shelf} takes the / after it, so no // appears"
      (is (= "/heidelberg.html" (url/fill "/{shelf}/{slug}.html" root nil))
          "not //heidelberg.html, a protocol-relative link to a host")
      (is (= "https://e.org/heidelberg" (url/fill "https://e.org/{shelf}/{slug}" root nil)))
      (is (= "heidelberg" (url/fill "{shelf}/{slug}" root nil)))
      (is (= "https://e.org/x/" (url/fill "https://e.org/x/{shelf}" root nil))))
    (testing "a shelved book keeps its slashes"
      (is (= "/confessions/reformed/heidelberg.html" (url/fill "/{shelf}/{slug}.html" heidelberg nil))))))

(deftest values-never-set-the-scheme-test
  (let [label {:form :label :label "sec:q1"}]
    (testing "a value's : is encoded while the scheme is unsettled"
      (is (= "sec%3Aq1.html" (url/fill "{label}.html" heidelberg label)))
      (is (= "xsec%3Aq1" (url/fill "x{label}" heidelberg label)))
      (is (= "javascript%3Aalert"
             (url/fill "{label}" heidelberg {:form :label :label "javascript:alert"})))
      (is (= "14%3A8" (url/fill "{locator}" (book-ref/book-id "bible" "romanos")
                                {:form :locator :locator "14:8"}))))
    (testing "the template's own : settles it, by its owner's choice"
      (is (= "heidelberg:sec:q1" (url/fill "{slug}:{label}" heidelberg label))))
    (testing "after a /, ? or # it stays readable"
      (is (= "https://e.org/r#sec:q1" (url/fill "https://e.org/r#{label}" heidelberg label)))
      (is (= "?v=14:8" (url/fill "?v={locator}" (book-ref/book-id "bible" "romanos")
                                 {:form :locator :locator "14:8"})))
      (is (= "/sec:q1.html" (url/fill "/{label}.html" heidelberg label)))
      (is (= "confessions/reformed/sec:q1" (url/fill "{shelf}/{label}" heidelberg label))
          "a filled shelf's own / settles it too"))))

(deftest fill-refuses-an-invalid-template-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown placeholder \{slgu\}"
                        (url/fill "https://e.org/{slgu}" heidelberg nil))))

(deftest template-problem-test
  (testing "a valid template has none"
    (is (nil? (url/template-problem every-placeholder)))
    (is (nil? (url/template-problem "https://example.org/fixed.html"))))
  (testing "an unknown placeholder names itself and the known ones"
    (let [p (url/template-problem "https://e.org/{slgu}")]
      (is (str/includes? p "unknown placeholder {slgu}"))
      (is (str/includes? p "{library}, {locator}, {shelf}, {slug}"))))
  (testing "a brace that is not part of a placeholder"
    (is (str/includes? (url/template-problem "https://e.org/{slug") "\"{\""))
    (is (str/includes? (url/template-problem "https://e.org/slug}") "\"}\""))
    (is (str/includes? (url/template-problem "https://e.org/{{slug}}") "not part of a placeholder")))
  (testing "an empty placeholder"
    (is (str/includes? (url/template-problem "https://e.org/{}") "unknown placeholder {}")))
  (testing "not a string, or blank"
    (is (str/includes? (url/template-problem 42) "must be a string"))
    (is (str/includes? (url/template-problem ["x"]) "must be a string"))
    (is (str/includes? (url/template-problem "  ") "blank"))))

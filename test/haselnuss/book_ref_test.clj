(ns haselnuss.book-ref-test
  "Fixtures for the book-reference grammar (`haselnuss.book-ref`,
  TASK-148), one section per acceptance criterion: every doc-10 example
  round-trips in short and canonical form (AC #1); a passage is a label, a
  label range or a locator by its `kind:` prefix (AC #2); every malformed
  case returns a `:malformed-book-ref` diagnostic rather than throwing
  (AC #3); and BookIds that differ only in `:shelf` are the same book for
  resolution (AC #4)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as ref]))

;; --- AC #1: round trip ------------------------------------------------------

(def ^:private doc-10-examples
  "Every reference doc-10 writes out, with what it must parse to."
  {"reformed:heidelberg"
   {:id {:library "reformed" :shelf [] :slug "heidelberg"}}

   "reformed:confessions/reformed/heidelberg"
   {:id {:library "reformed" :shelf ["confessions" "reformed"] :slug "heidelberg"}}

   "reformed:confessions/reformed/heidelberg#sec:q1"
   {:id {:library "reformed" :shelf ["confessions" "reformed"] :slug "heidelberg"}
    :passage {:form :label :label "sec:q1"}}

   "reformed:heidelberg#sec:q1..sec:q3"
   {:id {:library "reformed" :shelf [] :slug "heidelberg"}
    :passage {:form :range :from "sec:q1" :to "sec:q3"}}

   "bible:romanos#14:8-9"
   {:id {:library "bible" :shelf [] :slug "romanos"}
    :passage {:form :locator :locator "14:8-9"}}

   "bible:romanos#14:8"
   {:id {:library "bible" :shelf [] :slug "romanos"}
    :passage {:form :locator :locator "14:8"}}

   "bible:scripture/nt/romanos#14:8-9"
   {:id {:library "bible" :shelf ["scripture" "nt"] :slug "romanos"}
    :passage {:form :locator :locator "14:8-9"}}})

(deftest round-trip-test
  (doseq [[text parsed] doc-10-examples]
    (testing text
      (is (= parsed (ref/parse-book-ref text)))
      (is (= text (ref/format-book-ref (ref/parse-book-ref text)))))))

(deftest short-and-canonical-form-test
  (let [canonical (ref/book-id "reformed" ["confessions" "reformed"] "heidelberg")
        short-id (ref/book-id "reformed" "heidelberg")]
    (testing "a BookId with a shelf formats in canonical form"
      (is (= "reformed:confessions/reformed/heidelberg" (ref/format-book-id canonical)))
      (is (= "reformed:confessions/reformed/heidelberg" (ref/format-book-ref canonical))))
    (testing "a BookId without one formats in short form"
      (is (= [] (:shelf short-id)))
      (is (= "reformed:heidelberg" (ref/format-book-id short-id))))
    (testing "a passage is appended after #"
      (is (= "reformed:confessions/reformed/heidelberg#sec:q1"
             (ref/format-book-ref {:id canonical
                                   :passage {:form :label :label "sec:q1"}}))))))

;; --- AC #2: label, range or locator -----------------------------------------

(defn- passage-of [text] (:passage (ref/parse-book-ref (str "lib:book#" text))))

(deftest passage-disambiguation-test
  (testing "a passage starting with kind: is a label"
    (is (= {:form :label :label "sec:q1"} (passage-of "sec:q1")))
    (is (= {:form :label :label "thm:main-result"} (passage-of "thm:main-result")))
    (is (= {:form :label :label "my-kind2:x"} (passage-of "my-kind2:x")))
    (is (= {:form :label :label "sec:2.1"} (passage-of "sec:2.1"))
        "a single dot inside a label is not a range"))
  (testing "label..label is a label range"
    (is (= {:form :range :from "sec:q1" :to "sec:q3"} (passage-of "sec:q1..sec:q3")))
    (is (= {:form :range :from "sec:q1" :to "fig:x"} (passage-of "sec:q1..fig:x"))))
  (testing "anything else is a locator, kept as written"
    (doseq [loc ["14:8-9" "14:8" "1" "q1" "Q1:x" "2-sec:x" "14:8..15:2" "§3"]]
      (is (= {:form :locator :locator loc} (passage-of loc)) loc))))

;; --- AC #3: malformed input -------------------------------------------------

(def ^:private malformed-cases
  "Malformed input, each with a fragment its message must name."
  {;; structure
   "" "no \":\""
   "reformed" "no \":\""
   ":heidelberg" "library name is empty"
   "reformed:" "slug is empty"
   "reformed:confessions/" "slug is empty"
   "reformed:/heidelberg" "empty segment"
   "reformed:confessions//heidelberg" "empty segment"
   ;; names
   "Reformed:heidelberg" "library name \"Reformed\""
   "reformed:Heidelberg" "slug \"Heidelberg\""
   "reformed:Confessions/heidelberg" "shelf segment \"Confessions\""
   "reformed:-heidelberg" "slug \"-heidelberg\""
   "re_formed:heidelberg" "library name \"re_formed\""
   "reformed:heidel.berg" "slug \"heidel.berg\""
   "reformed:heidel:berg" "slug \"heidel:berg\""
   " reformed:heidelberg" "library name \" reformed\""
   "reformed:heidelberg " "slug \"heidelberg \""
   ;; passages
   "reformed:heidelberg#" "followed by a passage"
   "reformed:heidelberg#sec:q1#q2" "only one \"#\""
   "reformed:heidelberg##sec:q1" "only one \"#\""
   "reformed:heidelberg#sec:q1.." "dangling \"..\""
   "reformed:heidelberg#..sec:q1" "dangling \"..\""
   "reformed:heidelberg#.." "dangling \"..\""
   "bible:romanos#14:8.." "dangling \"..\""
   "reformed:heidelberg#sec:q1..14" "range end \"14\""
   "reformed:heidelberg#sec:q1..sec:q2..sec:q3" "more than one \"..\""
   "reformed:heidelberg#sec:" "label \"sec:\""
   "reformed:heidelberg#sec:q 1" "label \"sec:q 1\""
   "reformed:heidelberg#sec:." "label \"sec:.\""
   "reformed:heidelberg#sec:-" "label \"sec:-\""
   "reformed:heidelberg#sec:.q1" "label \"sec:.q1\""
   "bible:romanos#14 8" "locator \"14 8\""
   ;; Unicode whitespace that Java's ASCII `\s` would let through
   "bible:romanos#14 8" "locator \"14 8\""
   "bible:romanos#14 8" "locator \"14 8\""
   "bible:romanos#14　8" "locator \"14　8\""
   "bible:romanos#14}" "locator \"14}\""})

(deftest malformed-test
  (doseq [[text fragment] malformed-cases]
    (testing (pr-str text)
      (let [result (ref/parse-book-ref text)]
        (is (ref/malformed? result))
        (is (= {:type :malformed-book-ref :ref text}
               (select-keys result [:type :ref])))
        (is (str/includes? (:message result) fragment) (:message result))
        (is (str/includes? (:message result) (pr-str text))
            "the message names the reference as written")))))

(deftest malformed-non-string-test
  (doseq [x [nil 42 :reformed/heidelberg {:id 1} ["a:b"]]]
    (testing (pr-str x)
      (let [result (ref/parse-book-ref x)]
        (is (ref/malformed? result))
        (is (string? (:message result)))))))

(deftest format-rejects-invalid-input-test
  (doseq [x [(ref/parse-book-ref "Reformed:x") nil {} {:id nil} "a:b"]]
    (testing (pr-str x)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects a parsed book reference"
                            (ref/format-book-ref x))))))

(deftest parsed-refs-are-not-malformed-test
  (doseq [text (keys doc-10-examples)]
    (is (not (ref/malformed? (ref/parse-book-ref text))) text)))

;; --- AC #4: shelf-insensitive identity --------------------------------------

(deftest same-book-ignores-shelf-test
  (let [canonical (:id (ref/parse-book-ref "reformed:confessions/reformed/heidelberg"))
        short-id (:id (ref/parse-book-ref "reformed:heidelberg"))
        moved (:id (ref/parse-book-ref "reformed:creeds/heidelberg"))]
    (testing "ids differing only in :shelf are the same book"
      (is (ref/same-book? canonical short-id))
      (is (ref/same-book? canonical moved))
      (is (= (ref/book-key canonical) (ref/book-key short-id) (ref/book-key moved)))
      (is (= 1 (count (group-by ref/book-key [canonical short-id moved])))
          "book-key is usable as a map key for resolution"))
    (testing "a different library or slug is a different book"
      (is (not (ref/same-book? canonical (ref/book-id "lutheran" "heidelberg"))))
      (is (not (ref/same-book? canonical (ref/book-id "reformed" "westminster")))))
    (testing "the shelf is still kept, for canonical printing and stale-shelf checks"
      (is (not= canonical short-id)))))

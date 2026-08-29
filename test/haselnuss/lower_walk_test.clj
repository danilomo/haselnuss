(ns haselnuss.lower-walk-test
  "Regression coverage for TASK-85's port of `haselnuss.lower` onto
  `haselnuss.walk/rewrite`: a Figure whose `:content` (a `:one` position,
  sec4.3) lowers to a count other than one must still raise, naming the
  Figure, even though the check is now the walker's own generic
  `::walk/arity-error` rather than this namespace's former Figure-only
  `::invalid-figure-content` (AC #3). Kept as its own file rather than
  added to `lower-test`/`degradation-test`, since TASK-85's AC #5 requires
  both of those stay unmodified."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.lower :as lower]
            [haselnuss.walk :as walk]))

(def ^:private attr
  {:classes [] :props {}})

(defn- directive
  [directive-name fallback]
  {:t :directive :name directive-name :blocks [] :attr attr :fallback fallback})

(defn- figure
  [content]
  {:t :figure :content content :caption [] :attr attr})

(defn- doc
  [& blocks]
  {:meta {} :blocks (vec blocks)})

(deftest figure-content-arity-error-test
  (testing "content that lowers to zero blocks (a :drop fallback)"
    (let [d (directive "mystery" {:kind :drop})
          fig (figure d)]
      (try
        (lower/lower (doc fig) :html {})
        (is false "expected ::walk/arity-error to be raised")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::walk/arity-error (:type (ex-data e))))
          (is (= :content (:field (ex-data e))))
          (is (= :figure (:t (:node (ex-data e)))))
          (is (= 0 (:count (ex-data e))))))))
  (testing "content that lowers to two blocks (a :blocks fallback with two
            entries)"
    (let [d (directive "mystery" {:kind :blocks
                                  :blocks [{:t :para :inlines []}
                                           {:t :para :inlines []}]})
          fig (figure d)]
      (try
        (lower/lower (doc fig) :html {})
        (is false "expected ::walk/arity-error to be raised")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::walk/arity-error (:type (ex-data e))))
          (is (= :content (:field (ex-data e))))
          (is (= :figure (:t (:node (ex-data e)))))
          (is (= 2 (:count (ex-data e)))))))))

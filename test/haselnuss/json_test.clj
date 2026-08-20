(ns haselnuss.json-test
  "Exercises the AST<->JSON interchange in haselnuss.json: a full round-trip
  through every Block and Inline variant (TASK-3 AC #1), clear/catchable
  errors on unknown or malformed nodes in either direction (AC #2), and a
  malli.json-schema-generated JSON Schema for the AST (AC #3)."
  (:require [clojure.data.json :as data-json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [haselnuss.ast :as ast]
            [haselnuss.json :as json]))

(def attr
  "A minimal but complete Attr."
  {:classes [] :props {"data-x" "y"}})

(def every-inline
  "One example of every Inline variant (sec4.4), all in one vector, so a
  single Para below exercises all 18 in one place."
  [{:t :str :text "hi"}
   {:t :space}
   {:t :soft-break}
   {:t :line-break}
   {:t :emph :inlines [{:t :str :text "x"}]}
   {:t :strong :inlines [{:t :str :text "x"}]}
   {:t :strike :inlines [{:t :str :text "x"}]}
   {:t :small-caps :inlines [{:t :str :text "x"}]}
   {:t :sub :inlines [{:t :str :text "x"}]}
   {:t :sup :inlines [{:t :str :text "x"}]}
   {:t :code :text "x"}
   {:t :math-inline :tex "x"}
   {:t :link :target "https://example.com" :inlines [{:t :str :text "x"}] :attr attr}
   {:t :image :src "x.png" :alt "x" :attr attr}
   {:t :span :inlines [{:t :str :text "x"}] :attr attr}
   {:t :cross-ref :label "fig:tree" :suppress-prefix true}
   {:t :cite :items [{:key "smith2020" :mode :normal}
                     {:key "doe2021" :mode :author
                      :prefix [{:t :str :text "see"}]
                      :suffix [{:t :str :text "p. 3"}]}]}
   {:t :note :blocks [{:t :para :inlines [{:t :str :text "footnote"}]}]}])

(def document
  "A whole Document exercising every one of the 11 Block variants (sec4.3)
  and, via `every-inline`, every one of the 18 Inline variants (sec4.4),
  nested realistically rather than as a flat list."
  ;; `:top-level-division` is a keyword-valued enum, unlike every other
  ;; Meta field (TASK-53), so it is in the fixture rather than only in
  ;; the parser's own tests: it is the one place the JSON boundary has to
  ;; turn a string back into a keyword for Meta, and a silent failure
  ;; there would be a chaptered document that stops being chaptered on
  ;; the way through the interchange format.
  {:meta {:title [{:t :str :text "Sample"}] :authors ["A. Author"] :lang "en"
          :top-level-division :chapter}
   :blocks
   [{:t :section :level 1 :heading [{:t :str :text "Top"}] :attr attr
     :blocks
     [{:t :section :level 2 :heading [{:t :str :text "Sub"}] :attr attr :blocks []}
      {:t :para :inlines every-inline}
      {:t :list :ordered false :tight true
       :items [[{:t :para :inlines [{:t :str :text "item"}]}]] :attr attr}
      {:t :code-block :lang "clj" :text "(+ 1 2)" :attr attr}
      {:t :math-block :tex "x^2" :attr attr}
      {:t :figure :content {:t :code-block :text "x" :attr attr}
       :caption [{:t :str :text "fig caption"}] :attr attr}
      {:t :table
       :head {:cells [{:blocks [] :align :left}]}
       :rows [{:cells [{:blocks [{:t :para :inlines []}] :align :center :span 2}]}]
       :caption [{:t :str :text "table caption"}]
       :colspec [{:align :right :width "10em"}]
       :attr attr}
      {:t :block-quote :blocks [{:t :para :inlines [{:t :str :text "quoted"}]}]}
      {:t :directive :name "theorem" :blocks [] :attr attr
       :fallback {:kind :poster :src "poster.png" :caption [{:t :str :text "poster"}]}}
      {:t :include :src "chapters/intro.hzn"}
      {:t :thematic-break}]}]})

(def all-block-tags
  "Every Block variant tag (sec4.3)."
  #{:section :para :list :code-block :math-block :figure :table
    :block-quote :directive :include :thematic-break})

(def all-inline-tags
  "Every Inline variant tag (sec4.4)."
  #{:str :space :soft-break :line-break :emph :strong :strike :small-caps
    :sub :sup :code :math-inline :link :image :span :cross-ref :cite :note})

(defn- used-tags
  "Every `:t` value reachable anywhere inside `data`, found by walking the
  whole structure -- used to confirm the `document` fixture below actually
  contains every Block/Inline variant, rather than just claiming to."
  [data]
  (let [tags (atom #{})]
    (walk/postwalk (fn [x] (when (and (map? x) (contains? x :t)) (swap! tags conj (:t x))) x) data)
    @tags))

(deftest document-round-trip-test
  (testing "the fixture Document is itself valid, exercising every Block/Inline variant"
    (is (= 11 (count all-block-tags)))
    (is (= 18 (count all-inline-tags)))
    (is (set/subset? all-block-tags (used-tags document))
        "document should exercise every Block variant")
    (is (set/subset? all-inline-tags (used-tags document))
        "document should exercise every Inline variant")
    (is (ast/valid? ast/Document document) (pr-str (ast/explain ast/Document document))))
  (testing "AST -> JSON data -> AST round-trips to an equal data structure"
    (let [data (json/->json-data document)
          back (json/json-data->ast data)]
      (is (= document back))))
  (testing "AST -> JSON text -> AST round-trips to an equal data structure"
    (let [text (json/->json document)
          back (json/json->ast text)]
      (is (string? text))
      (is (= document back)))))

(deftest serializing-unknown-node-throws-test
  (testing "an unknown :t is a clear, catchable error rather than silently-wrong JSON"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (json/->json-data ast/Block {:t :bogus})))]
      (is (= ::json/invalid-ast (:type (ex-data e))))))
  (testing "a malformed node (missing a required key) is likewise a clear, catchable error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (json/->json-data ast/Block {:t :para})))] ;; missing :inlines
      (is (= ::json/invalid-ast (:type (ex-data e))))))
  (testing "encoding a full Document containing such a node also throws, rather than embedding it"
    (let [bad (update document :blocks conj {:t :bogus})]
      (is (thrown? clojure.lang.ExceptionInfo (json/->json bad))))))

(deftest deserializing-unknown-node-throws-test
  (testing "decoded JSON with an unknown tag is a clear, catchable error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (json/json->ast ast/Block "{\"t\":\"bogus\"}")))]
      (is (= ::json/invalid-json (:type (ex-data e))))))
  (testing "decoded JSON missing a required field is likewise a clear, catchable error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (json/json->ast ast/Block "{\"t\":\"para\"}")))] ;; missing inlines
      (is (= ::json/invalid-json (:type (ex-data e))))))
  (testing "syntactically invalid JSON text still raises (as a plain, catchable exception)"
    (is (thrown? Exception (json/json->ast "not json")))))

(deftest document-json-schema-test
  (testing "the JSON Schema is generated via malli.json-schema from ast/Document"
    (let [schema json/document-json-schema]
      (is (= "#/definitions/haselnuss.ast~1document" (:$ref schema)))
      (is (contains? (:definitions schema) "haselnuss.ast/block"))
      (is (contains? (:definitions schema) "haselnuss.ast/inline"))
      (is (= 11 (-> schema :definitions (get "haselnuss.ast/block") :oneOf count))
          "one oneOf branch per Block variant")
      (is (= 18 (-> schema :definitions (get "haselnuss.ast/inline") :oneOf count))
          "one oneOf branch per Inline variant")))
  (testing "the generated schema is itself well-formed, serializable JSON"
    (let [text (data-json/write-str json/document-json-schema)
          parsed (data-json/read-str text)]
      (is (string? text))
      (is (= "#/definitions/haselnuss.ast~1document" (get parsed "$ref")))
      (is (contains? (get parsed "definitions") "haselnuss.ast/block")))))

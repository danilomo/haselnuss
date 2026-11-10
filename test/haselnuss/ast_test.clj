(ns haselnuss.ast-test
  "Constructs every Block/Inline/Fallback node variant from SPEC.md sec4 as
  plain Clojure data and checks it validates against the malli schemas in
  haselnuss.ast (TASK-2 AC #5), plus a few structural/negative cases for the
  other acceptance criteria. `explain-summary-test` (TASK-111) covers
  `explain-summary`: a bad `:t`/`:kind` names the actual offending value and
  every value that would have matched rather than malli's own generic
  'invalid dispatch value', an ordinary field problem still reads in the
  schema's own field vocabulary, and neither ever inlines the raw schema
  data or a Java object identity hash."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]))

(def attr
  "A minimal but complete Attr."
  {:classes [] :props {}})

(def blocks-by-tag
  "One valid example of every Block variant (sec4.3), keyed by :t."
  {:section {:t :section :level 1 :heading [{:t :str :text "Intro"}]
             :blocks [] :attr attr}
   :para {:t :para :inlines [{:t :str :text "hi"}]}
   :list {:t :list :ordered false :tight true
          :items [[{:t :para :inlines []}]] :attr attr}
   :code-block {:t :code-block :lang "clj" :text "(+ 1 2)" :attr attr}
   :math-block {:t :math-block :tex "x^2" :attr attr}
   :figure {:t :figure :content {:t :code-block :text "x" :attr attr}
            :caption [] :attr attr}
   :table {:t :table
           :head {:cells [{:blocks []}]}
           :rows [{:cells [{:blocks [] :align :left :span 2}]}]
           :caption []
           :colspec [{:align :left :width "10em"}]
           :attr attr}
   :block-quote {:t :block-quote :blocks [{:t :para :inlines []}]}
   :directive {:t :directive :name "theorem" :blocks [] :attr attr
               :fallback {:kind :drop}}
   :include {:t :include :src "chapters/intro.hzn"}
   :book-quote {:t :book-quote :ref "bible:romanos#14:8-9"}
   :thematic-break {:t :thematic-break}})

(def inlines-by-tag
  "One valid example of every Inline variant (sec4.4), keyed by :t."
  {:str {:t :str :text "hi"}
   :space {:t :space}
   :soft-break {:t :soft-break}
   :line-break {:t :line-break}
   :emph {:t :emph :inlines [{:t :str :text "x"}]}
   :strong {:t :strong :inlines [{:t :str :text "x"}]}
   :strike {:t :strike :inlines [{:t :str :text "x"}]}
   :small-caps {:t :small-caps :inlines [{:t :str :text "x"}]}
   :sub {:t :sub :inlines [{:t :str :text "x"}]}
   :sup {:t :sup :inlines [{:t :str :text "x"}]}
   :code {:t :code :text "x"}
   :math-inline {:t :math-inline :tex "x"}
   :link {:t :link :target "https://example.com" :inlines [{:t :str :text "x"}]
          :attr attr}
   :image {:t :image :src "x.png" :alt "x" :attr attr}
   :span {:t :span :inlines [{:t :str :text "x"}] :attr attr}
   :cross-ref {:t :cross-ref :label "fig:tree" :suppress-prefix true}
   :book-ref {:t :book-ref :ref "bible:romanos#14:8"}
   :cite {:t :cite :items [{:key "smith2020" :mode :normal}
                           {:key "doe2021" :mode :author
                            :prefix [{:t :str :text "see"}]
                            :suffix [{:t :str :text "p. 3"}]}]}
   :note {:t :note :blocks [{:t :para :inlines []}]}
   :highlight {:t :highlight :inlines [{:t :str :text "x"}] :attr attr}
   :sticky-note {:t :sticky-note :blocks [{:t :para :inlines []}] :attr attr}})

(def fallbacks-by-kind
  "One valid example of every Fallback variant (sec4.5), keyed by :kind."
  {:blocks {:kind :blocks :blocks [{:t :para :inlines []}]}
   :poster {:kind :poster :src "poster.png"
            :caption [{:t :str :text "caption"}]}
   :transform {:kind :transform :rule "flatten"}
   :drop {:kind :drop}})

(deftest block-variants-test
  (testing "every Block variant from sec4.3 is constructible and valid"
    (is (= 12 (count blocks-by-tag)))
    (doseq [[tag node] blocks-by-tag]
      (testing (str tag)
        (is (ast/valid? ast/Block node) (pr-str (ast/explain ast/Block node)))))))

(deftest inline-variants-test
  (testing "every Inline variant from sec4.4 is constructible and valid"
    (is (= 21 (count inlines-by-tag)))
    (doseq [[tag node] inlines-by-tag]
      (testing (str tag)
        (is (ast/valid? ast/Inline node) (pr-str (ast/explain ast/Inline node)))))))

(deftest fallback-variants-test
  (testing "Fallback has exactly the four variants: blocks, poster, transform, drop"
    (is (= #{:blocks :poster :transform :drop} (set (keys fallbacks-by-kind))))
    (doseq [[kind node] fallbacks-by-kind]
      (testing (str kind)
        (is (ast/valid? ast/Fallback node) (pr-str (ast/explain ast/Fallback node)))))))

(deftest section-nesting-test
  (testing "Section nests via child blocks rather than a flat list"
    (let [inner {:t :section :level 2 :heading [{:t :str :text "Sub"}]
                 :blocks [] :attr attr}
          outer {:t :section :level 1 :heading [{:t :str :text "Top"}]
                 :blocks [inner] :attr attr}]
      (is (ast/valid? ast/Block outer))
      (is (= inner (-> outer :blocks first))))))

(deftest distinct-emphasis-types-test
  (testing "Emph/Strong/Strike/SmallCaps/Sub/Sup are distinct Inline node types"
    (let [tags #{:emph :strong :strike :small-caps :sub :sup}]
      (is (= 6 (count tags)))
      (doseq [tag tags]
        (is (ast/valid? ast/Inline (get inlines-by-tag tag)))))))

(deftest attr-on-referenceable-nodes-test
  (testing "referenceable/styleable node types carry an Attr, and it is required"
    (doseq [tag [:section :list :code-block :math-block :figure :table :directive]]
      (let [node (get blocks-by-tag tag)]
        (is (contains? node :attr) (str tag " should carry :attr"))
        (is (not (ast/valid? ast/Block (dissoc node :attr)))
            (str tag " without :attr should be invalid"))))
    (doseq [tag [:link :image :span :highlight :sticky-note]]
      (let [node (get inlines-by-tag tag)]
        (is (contains? node :attr) (str tag " should carry :attr"))
        (is (not (ast/valid? ast/Inline (dissoc node :attr)))
            (str tag " without :attr should be invalid"))))))

(deftest annotation-node-shapes-test
  (testing "TASK-116 AC #2: each is rejected when it carries the other's
            child field -- a :highlight holds Inlines, never Blocks, and a
            :sticky-note holds Blocks, never Inlines"
    (is (not (ast/valid? ast/Inline {:t :highlight
                                     :blocks [{:t :para :inlines []}]
                                     :attr attr})))
    (is (not (ast/valid? ast/Inline {:t :sticky-note
                                     :inlines [{:t :str :text "x"}]
                                     :attr attr})))
    (is (not (ast/valid? ast/Inline {:t :highlight
                                     :inlines [{:t :para :inlines []}]
                                     :attr attr}))
        ":highlight's children are Inlines, so a Block among them is invalid")
    (is (not (ast/valid? ast/Inline {:t :sticky-note
                                     :blocks [{:t :str :text "x"}]
                                     :attr attr}))
        ":sticky-note's children are Blocks, so an Inline among them is invalid")))

(def resolved-book-ref
  "A `:book-ref` carrying every optional field resolution fills in."
  {:t :book-ref :ref "bible:romanos#14:8"
   :passage {:form :locator :locator "14:8"}
   :canonical "bible:scripture/nt/romanos#14:8"
   :text [{:t :str :text "Romanos 14:8"}]
   :url "https://example.org/romanos#14:8"})

(def resolved-book-quote
  "A `:book-quote` carrying every optional field quote expansion fills in."
  {:t :book-quote :ref "reformed:heidelberg#sec:q1..sec:q3"
   :canonical "reformed:confessions/reformed/heidelberg#sec:q1..sec:q3"
   :title [{:t :emph :inlines [{:t :str :text "Heidelberg"}]}]
   :blocks [{:t :para :inlines [{:t :str :text "Q1."}]}]
   :url "https://example.org/heidelberg.html#sec:q1"})

(deftest book-node-shapes-test
  (testing "TASK-152 AC #3: the ref as written is the only required field"
    (is (ast/valid? ast/Inline {:t :book-ref :ref "bible:romanos#14:8"}))
    (is (ast/valid? ast/Block {:t :book-quote :ref "bible:romanos#14:8-9"}))
    (is (not (ast/valid? ast/Inline {:t :book-ref})))
    (is (not (ast/valid? ast/Block {:t :book-quote}))))
  (testing "a malformed ref is still schema-valid: judging it is the resolver's job"
    (is (ast/valid? ast/Inline {:t :book-ref :ref "not a ref"}))
    (is (ast/valid? ast/Block {:t :book-quote :ref "Bad:Ref"})))
  (testing "every resolved field is optional, and typed"
    (is (ast/valid? ast/Inline resolved-book-ref) (pr-str (ast/explain ast/Inline resolved-book-ref)))
    (is (ast/valid? ast/Block resolved-book-quote) (pr-str (ast/explain ast/Block resolved-book-quote)))
    (doseq [form [{:form :label :label "sec:q1"}
                  {:form :range :from "sec:q1" :to "sec:q3"}
                  {:form :locator :locator "14:8-9"}]]
      (is (ast/valid? ast/Inline (assoc resolved-book-ref :passage form)) (pr-str form)))
    (is (not (ast/valid? ast/Inline (assoc resolved-book-ref :passage {:form :chapter}))))
    (testing "each form requires its own fields"
      (doseq [bad [{:form :range} {:form :range :from "sec:q1"} {:form :label}
                   {:form :locator} {:form :label :locator "14:8"}]]
        (is (not (ast/valid? ast/Inline (assoc resolved-book-ref :passage bad))) (pr-str bad))))
    (is (not (ast/valid? ast/Inline (assoc resolved-book-ref :text "Romanos 14:8")))
        ":text is Inlines, like the Passage :title it comes from")
    (is (not (ast/valid? ast/Block (assoc resolved-book-quote :blocks [{:t :str :text "x"}]))))))

(deftest invalid-nodes-rejected-test
  (testing "malli schemas reject malformed nodes"
    (is (not (ast/valid? ast/Block {:t :bogus})))
    (is (not (ast/valid? ast/Block {:level 1})))
    (is (not (ast/valid? ast/Inline {:t :str})))
    (is (not (ast/valid? ast/Fallback {:kind :bogus})))))

(deftest explain-summary-test
  (testing "nil explanation (a valid value) summarizes to nil"
    (is (nil? (ast/explain-summary (ast/explain ast/Block {:t :para :inlines []})))))
  (testing "a bad :t (TASK-111's own dogfooding example) names the actual
            offending value and every value that would have matched,
            without inlining the full block schema or a Java object hash"
    (let [summary (ast/explain-summary
                   (ast/explain ast/Block {:t :not-a-real-node-type :nonsense true}))]
      (is (re-find #":not-a-real-node-type" summary))
      (is (re-find #":section" summary) "names a valid Block type")
      (is (re-find #":thematic-break" summary) "names every valid Block type, not just one")
      (is (not (re-find #"\[:multi" summary)) "never inlines the raw schema data")
      (is (not (re-find #"@" summary)) "never inlines a Java object identity hash")
      (is (< (count summary) 300) "far short of the ~1500 characters (pr-str explanation)")))
  (testing "the same treatment for a bad :kind on Fallback, not only Block (AC #3)"
    (let [summary (ast/explain-summary (ast/explain ast/Fallback {:kind :bogus}))]
      (is (re-find #":bogus" summary))
      (is (re-find #":drop" summary))
      (is (not (re-find #"\[:multi" summary)))))
  (testing "an ordinary field problem (not a dispatch failure) reads in the
            schema's own field vocabulary"
    (let [summary (ast/explain-summary (ast/explain ast/Block {:t :para}))]
      (is (re-find #":inlines" summary))
      (is (re-find #"missing required key" summary)))))

(deftest document-test
  (testing "a Document is Meta plus top-level Blocks, with open-ended front matter"
    (let [doc {:meta {:title [{:t :str :text "Title"}]
                      :authors ["A. Author"]
                      :lang "en"
                      :custom-front-matter-key "anything"}
               :blocks (vec (vals blocks-by-tag))}]
      (is (ast/valid? ast/Document doc) (pr-str (ast/explain ast/Document doc))))))

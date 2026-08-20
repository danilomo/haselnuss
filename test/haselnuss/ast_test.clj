(ns haselnuss.ast-test
  "Constructs every Block/Inline/Fallback node variant from SPEC.md sec4 as
  plain Clojure data and checks it validates against the malli schemas in
  haselnuss.ast (TASK-2 AC #5), plus a few structural/negative cases for the
  other acceptance criteria."
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
   :cite {:t :cite :items [{:key "smith2020" :mode :normal}
                           {:key "doe2021" :mode :author
                            :prefix [{:t :str :text "see"}]
                            :suffix [{:t :str :text "p. 3"}]}]}
   :note {:t :note :blocks [{:t :para :inlines []}]}})

(def fallbacks-by-kind
  "One valid example of every Fallback variant (sec4.5), keyed by :kind."
  {:blocks {:kind :blocks :blocks [{:t :para :inlines []}]}
   :poster {:kind :poster :src "poster.png"
            :caption [{:t :str :text "caption"}]}
   :transform {:kind :transform :rule "flatten"}
   :drop {:kind :drop}})

(deftest block-variants-test
  (testing "every Block variant from sec4.3 is constructible and valid"
    (is (= 11 (count blocks-by-tag)))
    (doseq [[tag node] blocks-by-tag]
      (testing (str tag)
        (is (ast/valid? ast/Block node) (pr-str (ast/explain ast/Block node)))))))

(deftest inline-variants-test
  (testing "every Inline variant from sec4.4 is constructible and valid"
    (is (= 18 (count inlines-by-tag)))
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
    (doseq [tag [:link :image :span]]
      (let [node (get inlines-by-tag tag)]
        (is (contains? node :attr) (str tag " should carry :attr"))
        (is (not (ast/valid? ast/Inline (dissoc node :attr)))
            (str tag " without :attr should be invalid"))))))

(deftest invalid-nodes-rejected-test
  (testing "malli schemas reject malformed nodes"
    (is (not (ast/valid? ast/Block {:t :bogus})))
    (is (not (ast/valid? ast/Block {:level 1})))
    (is (not (ast/valid? ast/Inline {:t :str})))
    (is (not (ast/valid? ast/Fallback {:kind :bogus})))))

(deftest document-test
  (testing "a Document is Meta plus top-level Blocks, with open-ended front matter"
    (let [doc {:meta {:title [{:t :str :text "Title"}]
                      :authors ["A. Author"]
                      :lang "en"
                      :custom-front-matter-key "anything"}
               :blocks (vec (vals blocks-by-tag))}]
      (is (ast/valid? ast/Document doc) (pr-str (ast/explain ast/Document doc))))))

(ns haselnuss.ext-test
  "Fixtures for `haselnuss.ext` (TASK-90/TASK-103): `raw` builds the
  `[:raw s]` node (unchanged, moved here from `haselnuss.extensions.
  sidecar`); `text`/`map-text` each accept a single `haselnuss.ast` node
  OR a vector of nodes (AC #9); `text->inlines` tokenizes plain text into
  `:str`/`:space` Inlines the way `haselnuss.parser`'s own (private)
  version does. This file exercises every function directly, against
  plain AST fixtures -- `haselnuss.extensions.rules-test`/`haselnuss.
  cli-test` cover the same functions reached from inside the sandbox,
  through a real `--transform` rule."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ext :as ext]))

;; --- fixtures ---------------------------------------------------------------

(defn- str-inline [text] {:t :str :text text})
(defn- space [] {:t :space})
(defn- emph [inlines] {:t :emph :inlines inlines})

(defn- section
  [heading blocks]
  {:t :section :level 1 :heading heading :blocks blocks :attr {:classes [] :props {}}})

(defn- para [inlines] {:t :para :inlines inlines})

(deftest raw-test
  (testing "raw builds [:raw s], coercing a non-string argument to a string
            (matching haselnuss.extensions.sidecar's own original,
            unchanged reasoning: a no-op for the common case, harmless
            for an uncommon one)"
    (is (= [:raw "verbatim"] (ext/raw "verbatim")))
    (is (= [:raw "42"] (ext/raw 42)))))

(deftest text-single-node-test
  (testing "text on a single node delegates straight to
            haselnuss.select/flatten-text"
    (is (= "Hello World"
           (ext/text (section [(str-inline "Hello") (space) (str-inline "World")] []))))
    (is (= "" (ext/text {:t :thematic-break})))))

(deftest text-vector-of-nodes-test
  (testing "text on a vector of Inline nodes -- the shape a :heading/
            :caption FIELD already is -- concatenates each element's own
            flattened text with no separator, reproducing the original
            spacing from the :space nodes already in the vector"
    (is (= "Hello World"
           (ext/text [(str-inline "Hello") (space) (emph [(str-inline "World")])]))))
  (testing "an empty vector yields an empty string"
    (is (= "" (ext/text []))))
  (testing "applies directly to a :heading field with no caller-side map"
    (let [heading [(str-inline "Chapter") (space) (str-inline "One")]]
      (is (= "Chapter One" (ext/text heading))))))

(deftest map-text-single-node-test
  (testing "map-text on a single node passes every descendant :str's own
            :text through f, leaving everything else (structure, :attr,
            non-:str nodes) unchanged"
    (let [node (section [(str-inline "hello") (space) (emph [(str-inline "world")])]
                        [(para [(str-inline "body")])])
          result (ext/map-text node str/upper-case)]
      (is (= [{:t :str :text "HELLO"} {:t :space} {:t :emph :inlines [{:t :str :text "WORLD"}]}]
             (:heading result)))
      (is (= [{:t :para :inlines [{:t :str :text "BODY"}]}] (:blocks result)))
      (is (= {:classes [] :props {}} (:attr result)) "non-:str fields survive untouched")))
  (testing "a node with no :str anywhere comes back unchanged"
    (is (= {:t :thematic-break} (ext/map-text {:t :thematic-break} str/upper-case)))))

(deftest map-text-vector-of-nodes-test
  (testing "map-text on a vector of nodes -- the same :heading/:caption
            shape text accepts -- maps every element independently,
            returning a vector back"
    (let [heading [(str-inline "hello") (space) (emph [(str-inline "world")])]
          result (ext/map-text heading str/upper-case)]
      (is (vector? result))
      (is (= [{:t :str :text "HELLO"} {:t :space} {:t :emph :inlines [{:t :str :text "WORLD"}]}]
             result))))
  (testing "an empty vector comes back empty"
    (is (= [] (ext/map-text [] str/upper-case)))))

(deftest map-text-reaches-every-scope-test
  (testing "map-text reaches a :str nested behind a field
            haselnuss.transform's own default-scope does NOT walk (a
            Table cell, sec4.3) -- it is scoped to walk/every-ref-kind,
            not haselnuss.transform's own narrower default"
    (let [table {:t :table
                 :head {:cells [{:blocks [(para [(str-inline "head")])]}]}
                 :rows [{:cells [{:blocks [(para [(str-inline "cell")])]}]}]
                 :caption [(str-inline "caption")]
                 :colspec []
                 :attr {:classes [] :props {}}}
          result (ext/map-text table str/upper-case)]
      (is (= "HEAD" (get-in result [:head :cells 0 :blocks 0 :inlines 0 :text])))
      (is (= "CELL" (get-in result [:rows 0 :cells 0 :blocks 0 :inlines 0 :text])))
      (is (= "CAPTION" (get-in result [:caption 0 :text]))))))

(deftest text->inlines-test
  (testing "splits on runs of non-whitespace/whitespace, exactly
            haselnuss.parser's own private text->inlines"
    (is (= [{:t :str :text "hi"} {:t :space} {:t :str :text "there"}]
           (ext/text->inlines "hi there"))))
  (testing "a run of whitespace collapses to one :space Inline, however
            many characters wide"
    (is (= [{:t :str :text "hi"} {:t :space} {:t :str :text "there"}]
           (ext/text->inlines "hi   there"))))
  (testing "an empty string yields an empty vector"
    (is (= [] (ext/text->inlines ""))))
  (testing "round-trips through text: tokenizing text's own output
            reproduces the original vector of Inlines a hand-built
            :heading/:caption already is"
    (let [heading [(str-inline "Hello") (space) (str-inline "World")]]
      (is (= heading (ext/text->inlines (ext/text heading)))))))
